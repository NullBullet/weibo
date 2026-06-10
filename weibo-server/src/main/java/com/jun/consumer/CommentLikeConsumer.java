package com.jun.consumer;

import com.jun.config.RabbitMQConfig;
import com.jun.dto.ToggleLikeDTO;
import com.jun.enumeration.TargetType;
import com.jun.mapper.PostMapper;
import com.jun.mq.message.CommentLikeMessage;
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

import static com.jun.config.RabbitMQConfig.COMMENT_LIKE_MAX_RETRY_COUNT;
import static com.jun.constant.RedisConstants.MQ_IDEMPOTENT_KEY;

@Component
@Slf4j
public class CommentLikeConsumer {

    @Resource
    private PostMapper postMapper;
    @Resource
    private RabbitTemplate rabbitTemplate;
    @Resource
    private RedisTemplate redisTemplate;

    @RabbitListener(
            queues = RabbitMQConfig.COMMENT_LIKE_QUEUE,
            containerFactory = "commentLikeFactory")
    public void handleCommentLike(CommentLikeMessage message, Channel channel, Message msg) throws IOException {

        long deliveryTag = msg.getMessageProperties().getDeliveryTag();

        // 幂等：检查消息是否已处理
        String key = MQ_IDEMPOTENT_KEY + message.getMessageId();
        Boolean exists = redisTemplate.hasKey(key);
        if (Boolean.TRUE.equals(exists)) {
            log.info("评论点赞消息已处理，直接ACK: {}", message.getMessageId());
            channel.basicAck(deliveryTag, false);
            return;
        }

        try {
            ToggleLikeDTO dto = new ToggleLikeDTO();
            dto.setUserId(message.getUserId());
            dto.setTargetId(message.getCommentId());
            dto.setTargetType(TargetType.COMMENT);

            if (Boolean.TRUE.equals(message.getIsLiked())) {
                postMapper.saveLikeInfo(dto);
                postMapper.updateCommentLikeCount(1, message.getCommentId());
            } else {
                postMapper.deleteLikeInfo(dto);
                postMapper.updateCommentLikeCount(-1, message.getCommentId());
            }

            // 幂等标记（7天）
            redisTemplate.opsForValue().set(key, "1", 7, TimeUnit.DAYS);

            // 手动ACK
            channel.basicAck(deliveryTag, false);
            log.info("评论点赞消息消费成功: commentId={}, isLiked={}",
                    message.getCommentId(), message.getIsLiked());

        } catch (Exception e) {
            Integer retryCount = message.getRetryCount();
            if (retryCount == null) {
                retryCount = 0;
            }

            log.error("评论点赞消费失败，第{}次重试", retryCount + 1, e);

            if (retryCount >= COMMENT_LIKE_MAX_RETRY_COUNT) {
                log.error("评论点赞达到最大重试次数，进入死信队列");
                channel.basicNack(deliveryTag, false, false);
                return;
            } else {
                message.setRetryCount(retryCount + 1);
                rabbitTemplate.convertAndSend(
                        RabbitMQConfig.COMMENT_LIKE_RETRY_QUEUE,
                        message
                );
            }

            channel.basicAck(deliveryTag, false);
        }
    }
}
