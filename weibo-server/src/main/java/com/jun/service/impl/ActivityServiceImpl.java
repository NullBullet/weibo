package com.jun.service.impl;

import com.jun.mapper.RewardActivityMapper;
import com.jun.service.ActivityService;
import com.jun.service.RewardService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import static com.jun.constant.RedisConstants.REWARD_ACTIVITY_STATUS_KEY;

@Service
@Slf4j
public class ActivityServiceImpl implements ActivityService {

    @Resource
    private RewardService rewardService;
    @Resource
    private RewardActivityMapper rewardActivityMapper;
    @Resource
    private RedisTemplate redisTemplate;

    @Override
    public void handleForwardActivity(Long originalStatusId, Long currentId, Long forwardStatusId) {
        // 优先从 Redis 缓存查询 statusId → activityId 映射
        String statusKey = REWARD_ACTIVITY_STATUS_KEY + originalStatusId;
        Object cachedActivityId = redisTemplate.opsForValue().get(statusKey);

        Long activityId = null;

        if (cachedActivityId != null) {
            // Redis 缓存命中
            activityId = Long.valueOf(cachedActivityId.toString());
        } else {
            // 缓存未命中 → 查 DB
            com.jun.entity.RewardActivity activity = rewardActivityMapper.getByStatusId(originalStatusId);
            if (activity == null) {
                return; // 没有抽奖活动
            }
            activityId = activity.getId();

            // 回填缓存（带 TTL 防缓存穿透）
            redisTemplate.opsForValue().set(statusKey, activityId.toString(), 1, java.util.concurrent.TimeUnit.DAYS);
        }

        // 处理转发参与
        rewardService.handleForwardReward(activityId, currentId, originalStatusId);
    }
}