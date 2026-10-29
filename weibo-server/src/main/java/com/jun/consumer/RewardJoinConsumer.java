package com.jun.consumer;

import com.jun.config.RabbitMQConfig;
import com.jun.entity.RewardRecord;
import com.jun.mapper.RewardActivityMapper;
import com.jun.mapper.RewardRecordMapper;
import com.jun.mq.message.RewardJoinMessage;
import com.jun.vo.RewardResultVO;
import com.jun.websocket.ChatWebSocketHandler;
import com.rabbitmq.client.Channel;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

import static com.jun.constant.RedisConstants.MQ_IDEMPOTENT_KEY;
import static com.jun.constant.RedisConstants.REWARD_ACTIVITY_INFO_KEY;

@Component
@Slf4j
public class RewardJoinConsumer {

    @Resource
    private RewardActivityMapper rewardActivityMapper;
    @Resource
    private RewardRecordMapper rewardRecordMapper;
    @Resource
    private RabbitTemplate rabbitTemplate;
    @Resource
    private RedisTemplate redisTemplate;
    @Resource
    private ChatWebSocketHandler chatWebSocketHandler;

    @RabbitListener(
            queues = RabbitMQConfig.REWARD_JOIN_QUEUE,
            containerFactory = "rewardJoinFactory")
    public void handleRewardJoin(RewardJoinMessage message, Channel channel, Message msg) throws IOException {

        long deliveryTag = msg.getMessageProperties().getDeliveryTag();

        // 幂等检查
        String key = MQ_IDEMPOTENT_KEY + message.getMessageId();
        Boolean exists = redisTemplate.hasKey(key);
        if (Boolean.TRUE.equals(exists)) {
            log.info("奖励参与消息已处理，直接ACK: {}", message.getMessageId());
            channel.basicAck(deliveryTag, false);
            return;
        }

        try {
            // 检查重复记录（唯一约束兜底）：命中说明参与记录已落库（典型场景是上次处理
            // 在插入后中途失败进入重试），补发参与成功通知；纯幂等重放已被上方 Redis
            // 幂等标记拦截，不会走到这里，因此不会重复推送
            Integer count = rewardRecordMapper.countByActivityIdAndUserId(
                    message.getActivityId(), message.getUserId());
            if (count != null && count > 0) {
                log.info("参与记录已存在: activityId={}, userId={}", message.getActivityId(), message.getUserId());
                redisTemplate.opsForValue().set(key, "1", 7, TimeUnit.DAYS);
                notifyRewardResult(message, true, RewardResultVO.REASON_JOINED);
                channel.basicAck(deliveryTag, false);
                return;
            }

            // 保存参与记录
            RewardRecord record = RewardRecord.builder()
                    .activityId(message.getActivityId())
                    .userId(message.getUserId())
                    .status(0)
                    .createTime(LocalDateTime.now())
                    .build();
            rewardRecordMapper.insert(record);

            // 更新活动参与人数
            Integer count1 = rewardActivityMapper.countParticipants(message.getActivityId());
            if (count1 == null) count1 = 0;
            rewardActivityMapper.updateParticipantCount(message.getActivityId(), count1);

            // 检查限量模式是否达到上限 → 结束活动
            Integer activityType = rewardActivityMapper.getActivityTypeById(message.getActivityId());
            if (activityType != null && activityType == 1) {
                Integer limit = rewardActivityMapper.getParticipantLimitById(message.getActivityId());
                if (limit != null && count1 >= limit) {
                    rewardActivityMapper.updateStatus(message.getActivityId(), 2);
                    // 同步更新 Redis 缓存中的活动状态
                    String infoKey = REWARD_ACTIVITY_INFO_KEY + message.getActivityId();
                    redisTemplate.opsForHash().put(infoKey, "status", "2");
                    log.info("限量活动已达上限，自动结束: activityId={}", message.getActivityId());
                }
            }

            // 幂等标记（7天）
            redisTemplate.opsForValue().set(key, "1", 7, TimeUnit.DAYS);

            // 参与记录落库成功后推送抽奖参与结果（推送失败仅记录日志，不影响 ACK）
            notifyRewardResult(message, true, RewardResultVO.REASON_JOINED);

            channel.basicAck(deliveryTag, false);
            log.info("奖励参与记录保存成功: activityId={}, userId={}",
                    message.getActivityId(), message.getUserId());

        } catch (Exception e) {
            Integer retryCount = message.getRetryCount();
            if (retryCount == null) {
                retryCount = 0;
            }

            log.error("奖励参与消费失败，第{}次重试", retryCount + 1, e);

            if (retryCount >= RabbitMQConfig.REWARD_MAX_RETRY_COUNT) {
                log.error("奖励参与达到最大重试次数，进入死信队列");
                // 重试耗尽仍未落库，推送失败结果，前端据此提示参与失败
                notifyRewardResult(message, false, RewardResultVO.REASON_SAVE_FAILED);
                channel.basicNack(deliveryTag, false, false);
                return;
            } else {
                message.setRetryCount(retryCount + 1);
                rabbitTemplate.convertAndSend(
                        RabbitMQConfig.REWARD_JOIN_RETRY_QUEUE,
                        message
                );
            }

            channel.basicAck(deliveryTag, false);
        }
    }

    /**
     * 抽奖参与结果推送：joined=true 表示参与记录已落库；落库失败进死信时 joined=false。
     * 推送异常完全吞掉，绝不影响已完成的持久化与 ACK 流程。
     */
    private void notifyRewardResult(RewardJoinMessage message, boolean joined, String reason) {
        try {
            chatWebSocketHandler.sendNotification(
                    message.getUserId(),
                    RewardResultVO.builder()
                            .type(RewardResultVO.TYPE)
                            .activityId(message.getActivityId())
                            .statusId(message.getStatusId())
                            .joined(joined)
                            .reason(reason)
                            .build(),
                    RewardResultVO.TYPE
            );
        } catch (Exception e) {
            log.warn("抽奖参与结果推送失败: activityId={}, userId={}",
                    message.getActivityId(), message.getUserId(), e);
        }
    }
}