package com.jun.consumer;

import com.jun.config.RabbitMQConfig;
import com.jun.mapper.NotificationsMapper;
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
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import static com.jun.config.RabbitMQConfig.NOTICE_COMMENT_MAX_RETRY_COUNT;
import static com.jun.constant.RedisConstants.MQ_IDEMPOTENT_KEY;

@Component
@Slf4j
public class NoticeCommentConsumer {

    @Resource
    private NotificationsMapper notificationsMapper;
    @Resource
    private RabbitTemplate rabbitTemplate;
    @Resource
    private RedisTemplate redisTemplate;

    @RabbitListener(
            queues = RabbitMQConfig.NOTICE_COMMENT_QUEUE,
            containerFactory = "noticeCommentFactory")
    public void handleNoticeComment(CommentMessage message, Channel channel, Message msg) throws IOException {

        long deliveryTag = msg.getMessageProperties().getDeliveryTag();

        // 幂等检查
        String key = MQ_IDEMPOTENT_KEY + message.getMessageId();
        Boolean exists = redisTemplate.hasKey(key);
        if (Boolean.TRUE.equals(exists)) {
            log.info("评论通知消息已处理，直接ACK: {}", message.getMessageId());
            channel.basicAck(deliveryTag, false);
            return;
        }

        try {
            Long senderId = message.getUserId();
            Long statusId = message.getStatusId();
            Long parentId = message.getParentId();
            Long replyUserId = message.getReplyUserId();
            Long commentId = message.getCommentId();

            // 确定接收者
            Long receiverId;
            Integer targetType;
            Long targetId;

            if (parentId == null) {
                // 一级评论：通知帖子作者
                receiverId = notificationsMapper.getOwnerByTargetId(1, statusId);
                targetType = 1; // POST
                targetId = statusId;
            } else {
                // 回复评论：通知被回复的人
                receiverId = replyUserId;
                targetType = 2; // COMMENT
                targetId = parentId;
            }

            // 自己回复自己，不写入通知
            if (receiverId == null || Objects.equals(receiverId, senderId)) {
                log.info("跳过自己回复自己的通知: receiverId={}, senderId={}", receiverId, senderId);
                redisTemplate.opsForValue().set(key, "1", 7, TimeUnit.DAYS);
                channel.basicAck(deliveryTag, false);
                return;
            }

            // 写入评论通知表
            notificationsMapper.insertCommentNotification(receiverId, senderId, targetType, targetId, commentId);

            // 幂等标记
            redisTemplate.opsForValue().set(key, "1", 7, TimeUnit.DAYS);

            channel.basicAck(deliveryTag, false);
            log.info("评论通知消息消费成功: receiverId={}, senderId={}, commentId={}",
                    receiverId, senderId, commentId);

        } catch (Exception e) {
            Integer retryCount = message.getRetryCount();
            if (retryCount == null) {
                retryCount = 0;
            }

            log.error("评论通知消费失败，第{}次重试", retryCount + 1, e);

            if (retryCount >= NOTICE_COMMENT_MAX_RETRY_COUNT) {
                log.error("评论通知达到最大重试次数，进入死信队列");
                channel.basicNack(deliveryTag, false, false);
                return;
            } else {
                message.setRetryCount(retryCount + 1);
                rabbitTemplate.convertAndSend(
                        RabbitMQConfig.NOTICE_COMMENT_RETRY_QUEUE,
                        message
                );
            }

            channel.basicAck(deliveryTag, false);
        }
    }
}
