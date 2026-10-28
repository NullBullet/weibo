package com.jun.consumer;

import com.jun.config.RabbitMQConfig;
import com.jun.mapper.CommentsMapper;
import com.jun.mq.message.CommentMessage;
import com.rabbitmq.client.Channel;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import static com.jun.config.RabbitMQConfig.COMMENT_MAX_RETRY_COUNT;
import static com.jun.constant.RedisConstants.MQ_IDEMPOTENT_KEY;

@Component
@Slf4j
public class CommentConsumer {

    @Resource
    private CommentsMapper commentsMapper;
    @Resource
    private RabbitTemplate rabbitTemplate;
    @Resource
    private RedisTemplate redisTemplate;

    @RabbitListener(
            queues = RabbitMQConfig.COMMENT_QUEUE,
            containerFactory = "commentFactory")
    public void handleComment(CommentMessage message, Channel channel, Message msg) throws IOException {

        long deliveryTag = msg.getMessageProperties().getDeliveryTag();

        // 幂等：检查消息是否已处理
        String key = MQ_IDEMPOTENT_KEY + RabbitMQConfig.COMMENT_QUEUE + ":" + message.getMessageId();
        Boolean exists = redisTemplate.hasKey(key);
        if (Boolean.TRUE.equals(exists)) {
            log.info("评论消息已处理，直接ACK: {}", message.getMessageId());
            channel.basicAck(deliveryTag, false);
            return;
        }

        try {
            // DB: statuses.comments_count +1（评论已在 Service 层同步插入）
            commentsMapper.updateStatusCommentCount(message.getStatusId());

            // 幂等标记（7天）
            redisTemplate.opsForValue().set(key, "1", 7, TimeUnit.DAYS);

            // 手动ACK
            channel.basicAck(deliveryTag, false);
            log.info("评论消息消费成功: statusId={}", message.getStatusId());

        } catch (Exception e) {
            Integer retryCount = message.getRetryCount();
            if (retryCount == null) {
                retryCount = 0;
            }

            log.error("评论消费失败，第{}次重试", retryCount + 1, e);

            if (retryCount >= COMMENT_MAX_RETRY_COUNT) {
                log.error("评论达到最大重试次数，进入死信队列");
                channel.basicNack(deliveryTag, false, false);
                return;
            } else {
                message.setRetryCount(retryCount + 1);
                rabbitTemplate.convertAndSend(
                        RabbitMQConfig.COMMENT_RETRY_QUEUE,
                        message
                );
            }

            channel.basicAck(deliveryTag, false);
        }
    }
}
