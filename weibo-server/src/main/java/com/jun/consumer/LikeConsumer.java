package com.jun.consumer;

import com.jun.config.RabbitMQConfig;
import com.jun.dto.ToggleLikeDTO;
import com.jun.mapper.PostMapper;
import com.jun.mq.message.LikeMessage;
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

import static com.jun.config.RabbitMQConfig.MAX_RETRY_COUNT;
import static com.jun.constant.RedisConstants.MQ_IDEMPOTENT_KEY;

@Component
@Slf4j
public class LikeConsumer {

    @Resource
    private PostMapper postMapper;
    @Resource
    private RabbitTemplate rabbitTemplate;
    @Resource
    private RedisTemplate redisTemplate;

    @RabbitListener(
            queues = RabbitMQConfig.LIKE_QUEUE,
            containerFactory = "likeFactory")
    public void handleLike(LikeMessage message, Channel channel, Message msg) throws IOException {

        long deliveryTag = msg.getMessageProperties().getDeliveryTag();

        String key = MQ_IDEMPOTENT_KEY + message.getMessageId();
        Boolean exists = redisTemplate.hasKey(key);
        if(Boolean.TRUE.equals(exists)){
            log.info("消息已处理，直接ACK:{}", message.getMessageId());
            channel.basicAck(deliveryTag, false);
            return;
        }

        try {
            ToggleLikeDTO dto = new ToggleLikeDTO();
            dto.setUserId(message.getUserId());
            dto.setTargetId(message.getStatusId());
            dto.setTargetType(message.getTargetType());
            if (Boolean.TRUE.equals(message.getIsLiked())) {
                postMapper.saveLikeInfo(dto);
                postMapper.updateStatusLikeCount(1, message.getStatusId());
                redisTemplate.opsForValue().set(key, "1", 7, TimeUnit.DAYS);
            } else {
                postMapper.deleteLikeInfo(dto);
                postMapper.updateStatusLikeCount(-1, message.getStatusId());
                redisTemplate.opsForValue().set(key, "1", 7, TimeUnit.DAYS);
            }

            //手动ack
            channel.basicAck(deliveryTag, false);
            log.info("消息消费成功");

        } catch (Exception e) {

            Integer retryCount = message.getRetryCount();

            if (retryCount == null) {
                retryCount = 0;
            }

            log.error("消费失败，第{}次重试", retryCount + 1, e);

            if (retryCount >= MAX_RETRY_COUNT) {

                log.error("达到最大重试次数，进入死信队列");

                rabbitTemplate.convertAndSend(
                        RabbitMQConfig.LIKE_DLQ,
                        message
                );

            } else {

                message.setRetryCount(retryCount + 1);

                rabbitTemplate.convertAndSend(
                        RabbitMQConfig.LIKE_RETRY_QUEUE,
                        message
                );
            }

            channel.basicAck(deliveryTag, false);
        }
    }
}