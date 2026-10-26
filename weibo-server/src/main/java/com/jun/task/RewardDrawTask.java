package com.jun.task;

import com.jun.entity.RewardActivity;
import com.jun.mapper.RewardActivityMapper;
import com.jun.mq.message.RewardWinMessage;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Random;
import java.util.UUID;

import static com.jun.config.RabbitMQConfig.POST_REWARD_WIN;
import static com.jun.config.RabbitMQConfig.WEIBO_TOPIC_EXCHANGE;

/**
 * 抽奖开奖定时任务
 */
@Slf4j
@Component
public class RewardDrawTask {

    private static final Random RANDOM = new Random();
    @Resource
    private RewardActivityMapper rewardActivityMapper;
    @Resource
    private RabbitTemplate rabbitTemplate;
    @Resource
    private RedisTemplate redisTemplate;

    /**
     * 每分钟执行一次，检查需要开奖的活动
     */
    @Scheduled(cron = "0 * * * * *")
    public void performDraw() {
        // 查询所有已结束但未开奖的活动
        List<RewardActivity> activities = rewardActivityMapper.getActivitiesToDraw();
        if (activities == null || activities.isEmpty()) {
            return;
        }

        for (RewardActivity activity : activities) {
            try {
                drawForActivity(activity);
            } catch (Exception e) {
                log.error("开奖失败: activityId={}", activity.getId(), e);
            }
        }
    }

    private void drawForActivity(RewardActivity activity) {
        Long activityId = activity.getId();
        Integer winnerCount = activity.getWinnerCount();
        if (winnerCount == null || winnerCount <= 0) {
            log.warn("活动未设置中奖人数: activityId={}", activityId);
            return;
        }

        // 获取所有参与用户
        List<Long> userIds = rewardActivityMapper.getParticipantUserIds(activityId);
        if (userIds == null || userIds.isEmpty()) {
            log.warn("活动无参与用户，跳过开奖: activityId={}", activityId);
            rewardActivityMapper.markActivityDrawn(activityId);
            return;
        }

        // 随机抽取中奖用户
        int actualWinnerCount = Math.min(winnerCount, userIds.size());
        for (int i = 0; i < actualWinnerCount; i++) {
            int pickIndex = RANDOM.nextInt(userIds.size());
            Long winnerId = userIds.remove(pickIndex);

            // 更新中奖记录
            rewardActivityMapper.markWinner(activityId, winnerId);

            // 发送中奖通知消息
            RewardWinMessage winMessage = RewardWinMessage.builder()
                    .messageId(UUID.randomUUID().toString())
                    .activityId(activityId)
                    .rewardName(activity.getRewardName())
                    .userId(winnerId)
                    .retryCount(0)
                    .build();

            CorrelationData correlationData = new CorrelationData(winMessage.getMessageId());
            rabbitTemplate.convertAndSend(
                    WEIBO_TOPIC_EXCHANGE,
                    POST_REWARD_WIN,
                    winMessage,
                    correlationData
            );

            log.info("开奖中奖: activityId={}, winnerId={}", activityId, winnerId);
        }

        // 标记活动已开奖
        rewardActivityMapper.markActivityDrawn(activityId);
        log.info("开奖完成: activityId={}, 中奖人数={}", activityId, actualWinnerCount);
    }
}