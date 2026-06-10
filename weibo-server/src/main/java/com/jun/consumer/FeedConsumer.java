package com.jun.consumer;

import com.jun.config.RabbitMQConfig;
import com.jun.mq.message.FeedMessage;
import com.rabbitmq.client.Channel;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static com.jun.config.RabbitMQConfig.FEED_MAX_RETRY_COUNT;
import static com.jun.constant.RedisConstants.FEED_USER_KEY;
import static com.jun.constant.RedisConstants.FOLLOW_FANS_KEY;
import static com.jun.constant.RedisConstants.MQ_IDEMPOTENT_KEY;

@Component
@Slf4j
public class FeedConsumer {

    @Resource
    private RabbitTemplate rabbitTemplate;
    @Resource
    private RedisTemplate redisTemplate;

    @RabbitListener(
            queues = RabbitMQConfig.FEED_QUEUE,
            containerFactory = "feedFactory")
    public void handleFeed(FeedMessage message, Channel channel, Message msg) throws IOException {

        long deliveryTag = msg.getMessageProperties().getDeliveryTag();

        // 幂等：检查消息是否已处理
        String key = MQ_IDEMPOTENT_KEY + message.getMessageId();
        Boolean exists = redisTemplate.hasKey(key);
        if (Boolean.TRUE.equals(exists)) {
            log.info("Feed消息已处理，直接ACK: {}", message.getMessageId());
            channel.basicAck(deliveryTag, false);
            return;
        }

        try {
            // 1. 获取发帖人的粉丝列表
            String fansKey = FOLLOW_FANS_KEY + message.getUserId();
            Set<String> fans = redisTemplate.opsForSet().members(fansKey);

            if (fans != null && !fans.isEmpty()) {
                // 2. 推送到每个粉丝的收件箱
                for (String fanId : fans) {
                    String feedKey = FEED_USER_KEY + fanId;
                    redisTemplate.opsForZSet().add(
                            feedKey,
                            message.getStatusId().toString(),
                            message.getCreateTime().doubleValue()
                    );
                }
            }

            // 幂等标记（7天）
            redisTemplate.opsForValue().set(key, "1", 7, TimeUnit.DAYS);

            // 手动ACK
            channel.basicAck(deliveryTag, false);
            log.info("Feed消息消费成功: statusId={}, userId={}, fans={}",
                    message.getStatusId(), message.getUserId(),
                    fans != null ? fans.size() : 0);

        } catch (Exception e) {
            Integer retryCount = message.getRetryCount();
            if (retryCount == null) {
                retryCount = 0;
            }

            log.error("Feed消费失败，第{}次重试", retryCount + 1, e);

            if (retryCount >= FEED_MAX_RETRY_COUNT) {
                log.error("Feed达到最大重试次数，进入死信队列");
                channel.basicNack(deliveryTag, false, false);
                return;
            } else {
                message.setRetryCount(retryCount + 1);
                rabbitTemplate.convertAndSend(
                        RabbitMQConfig.FEED_RETRY_QUEUE,
                        message
                );
            }

            channel.basicAck(deliveryTag, false);
        }
    }
}
