package com.jun.consumer;

import com.jun.config.RabbitMQConfig;
import com.jun.mapper.NotificationsMapper;
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

import static com.jun.config.RabbitMQConfig.NOTICE_MAX_RETRY_COUNT;
import static com.jun.constant.RedisConstants.MQ_IDEMPOTENT_KEY;

@Component
@Slf4j
public class NoticeConsumer {

    @Resource
    private NotificationsMapper notificationsMapper;
    @Resource
    private RabbitTemplate rabbitTemplate;
    @Resource
    private RedisTemplate redisTemplate;

    @RabbitListener(
            queues = RabbitMQConfig.NOTICE_QUEUE,
            containerFactory = "noticeFactory")
    public void handleNotice(LikeMessage message, Channel channel, Message msg) throws IOException {

        long deliveryTag = msg.getMessageProperties().getDeliveryTag();

        // 幂等检查
        String key = MQ_IDEMPOTENT_KEY + message.getMessageId();
        Boolean exists = redisTemplate.hasKey(key);
        if (Boolean.TRUE.equals(exists)) {
            log.info("通知消息已处理，直接ACK: {}", message.getMessageId());
            channel.basicAck(deliveryTag, false);
            return;
        }

        try {
            Integer targetType = message.getTargetType() != null ? message.getTargetType().getCode() : 1;
            Long targetId = message.getStatusId();

            // 获取目标作者（接收通知的人）
            Long receiverId = notificationsMapper.getOwnerByTargetId(targetType, targetId);

            if (receiverId != null) {
                if (Boolean.TRUE.equals(message.getIsLiked())) {
                    // 点赞：upsert，like_count +1，is_read = 0
                    notificationsMapper.upsertLikeNotification(receiverId, targetType, targetId);
                } else {
                    // 取消点赞：like_count -1，如果为 0 则删除
                    notificationsMapper.decrementLikeNotification(receiverId, targetType, targetId);
                    notificationsMapper.deleteZeroCount(receiverId, targetType, targetId);
                }
            }

            // 幂等标记
            redisTemplate.opsForValue().set(key, "1", 7, TimeUnit.DAYS);

            channel.basicAck(deliveryTag, false);
            log.info("通知消息消费成功: userId={}, statusId={}",
                    message.getUserId(), message.getStatusId());

        } catch (Exception e) {
            Integer retryCount = message.getRetryCount();
            if (retryCount == null) {
                retryCount = 0;
            }

            log.error("通知消费失败，第{}次重试", retryCount + 1, e);

            if (retryCount >= NOTICE_MAX_RETRY_COUNT) {
                log.error("通知达到最大重试次数，进入死信队列");
                channel.basicNack(deliveryTag, false, false);
                return;
            } else {
                message.setRetryCount(retryCount + 1);
                rabbitTemplate.convertAndSend(
                        RabbitMQConfig.NOTICE_RETRY_QUEUE,
                        message
                );
            }

            channel.basicAck(deliveryTag, false);
        }
    }
}
