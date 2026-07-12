package com.jun.consumer;

import com.jun.config.RabbitMQConfig;
import com.jun.mapper.RewardActivityMapper;
import com.jun.mq.message.RewardWinMessage;
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

import static com.jun.constant.RedisConstants.MQ_IDEMPOTENT_KEY;

@Component
@Slf4j
public class RewardWinConsumer {

    @Resource
    private RewardActivityMapper rewardActivityMapper;
    @Resource
    private RabbitTemplate rabbitTemplate;
    @Resource
    private RedisTemplate redisTemplate;

    @RabbitListener(
            queues = RabbitMQConfig.REWARD_WIN_QUEUE,
            containerFactory = "rewardWinFactory")
    public void handleRewardWin(RewardWinMessage message, Channel channel, Message msg) throws IOException {

        long deliveryTag = msg.getMessageProperties().getDeliveryTag();

        // 幂等检查
        String key = MQ_IDEMPOTENT_KEY + message.getMessageId();
        Boolean exists = redisTemplate.hasKey(key);
        if (Boolean.TRUE.equals(exists)) {
            log.info("中奖通知消息已处理，直接ACK: {}", message.getMessageId());
            channel.basicAck(deliveryTag, false);
            return;
        }

        try {
            // 写入系统通知到 notification_system 表
            rewardActivityMapper.insertWinNotification(
                    message.getUserId(),
                    message.getActivityId(),
                    "恭喜你获得「" + message.getRewardName() + "」，请前往活动详情页查看"
            );

            // 幂等标记（7天）
            redisTemplate.opsForValue().set(key, "1", 7, TimeUnit.DAYS);

            channel.basicAck(deliveryTag, false);
            log.info("中奖通知发送成功: activityId={}, userId={}, rewardName={}",
                    message.getActivityId(), message.getUserId(), message.getRewardName());

        } catch (Exception e) {
            Integer retryCount = message.getRetryCount();
            if (retryCount == null) {
                retryCount = 0;
            }

            log.error("中奖通知消费失败，第{}次重试", retryCount + 1, e);

            if (retryCount >= RabbitMQConfig.REWARD_MAX_RETRY_COUNT) {
                log.error("中奖通知达到最大重试次数，进入死信队列");
                channel.basicNack(deliveryTag, false, false);
                return;
            } else {
                message.setRetryCount(retryCount + 1);
                rabbitTemplate.convertAndSend(
                        RabbitMQConfig.REWARD_WIN_RETRY_QUEUE,
                        message
                );
            }

            channel.basicAck(deliveryTag, false);
        }
    }
}