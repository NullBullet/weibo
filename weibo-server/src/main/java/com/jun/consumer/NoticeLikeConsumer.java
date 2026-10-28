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
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import static com.jun.config.RabbitMQConfig.NOTICE_POST_LIKE_MAX_RETRY_COUNT;
import static com.jun.constant.RedisConstants.MQ_IDEMPOTENT_KEY;

@Component
@Slf4j
public class NoticeLikeConsumer {

    @Resource
    private NotificationsMapper notificationsMapper;
    @Resource
    private RabbitTemplate rabbitTemplate;
    @Resource
    private RedisTemplate redisTemplate;

    @RabbitListener(
            queues = RabbitMQConfig.NOTICE_POST_LIKE_QUEUE,
            containerFactory = "noticeFactory")
    public void handleLikeNotice(LikeMessage message, Channel channel, Message msg) throws IOException {

        long deliveryTag = msg.getMessageProperties().getDeliveryTag();

        // 幂等检查
        String key = MQ_IDEMPOTENT_KEY + RabbitMQConfig.NOTICE_POST_LIKE_QUEUE + ":" + message.getMessageId();
        Boolean exists = redisTemplate.hasKey(key);
        if (Boolean.TRUE.equals(exists)) {
            log.info("点赞通知消息已处理，直接ACK: {}", message.getMessageId());
            channel.basicAck(deliveryTag, false);
            return;
        }

        try {
            Long senderId = message.getUserId();
            Integer targetType = message.getTargetType() != null ? message.getTargetType().getCode() : 1;
            Long targetId = message.getStatusId();

            // 1. 获取目标作者（接收通知的人）
            Long receiverId = notificationsMapper.getOwnerByTargetId(targetType, targetId);

            // 2. 目标不存在或被删除 → 跳过
            if (receiverId == null) {
                log.warn("目标不存在，跳过: targetType={}, targetId={}", targetType, targetId);
                redisTemplate.opsForValue().set(key, "1", 7, TimeUnit.DAYS);
                channel.basicAck(deliveryTag, false);
                return;
            }

            // 3. 点赞自己的内容 → 跳过
            if (Objects.equals(senderId, receiverId)) {
                log.info("点赞自己的内容，跳过通知: userId={}", senderId);
                redisTemplate.opsForValue().set(key, "1", 7, TimeUnit.DAYS);
                channel.basicAck(deliveryTag, false);
                return;
            }

            // 4. 点赞/取消 → 更新 notification_like
            if (Boolean.TRUE.equals(message.getIsLiked())) {
                notificationsMapper.upsertLikeNotification(receiverId, targetType, targetId);
            } else {
                notificationsMapper.decrementLikeNotification(receiverId, targetType, targetId);
                notificationsMapper.deleteZeroCount(receiverId, targetType, targetId);
            }

            // 幂等标记
            redisTemplate.opsForValue().set(key, "1", 7, TimeUnit.DAYS);

            channel.basicAck(deliveryTag, false);
            log.info("点赞通知消费成功: userId={}, statusId={}",
                    message.getUserId(), message.getStatusId());

        } catch (Exception e) {
            Integer retryCount = message.getRetryCount();
            if (retryCount == null) {
                retryCount = 0;
            }

            log.error("点赞通知消费失败，第{}次重试", retryCount + 1, e);

            if (retryCount >= NOTICE_POST_LIKE_MAX_RETRY_COUNT) {
                log.error("点赞通知达到最大重试次数，进入死信队列");
                channel.basicNack(deliveryTag, false, false);
                return;
            } else {
                message.setRetryCount(retryCount + 1);
                rabbitTemplate.convertAndSend(
                        RabbitMQConfig.NOTICE_POST_LIKE_RETRY_QUEUE,
                        message
                );
            }

            channel.basicAck(deliveryTag, false);
        }
    }
}
