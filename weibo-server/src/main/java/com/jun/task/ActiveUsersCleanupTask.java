package com.jun.task;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import static com.jun.constant.RedisConstants.ACTIVE_USERS_KEY;

/**
 * 定时清理活跃用户 ZSET 中超过 7 天的记录
 */
@Slf4j
@Component
public class ActiveUsersCleanupTask {

    /** 7 天毫秒数 */
    private static final long SEVEN_DAYS_MS = 7 * 24 * 60 * 60 * 1000L;

    @Resource
    private RedisTemplate redisTemplate;

    /**
     * 每天 0:00 执行
     */
    @Scheduled(cron = "0 0 0 * * ?")
    public void cleanup() {
        long cutoff = System.currentTimeMillis() - SEVEN_DAYS_MS;

        Long removed = redisTemplate.opsForZSet().removeRangeByScore(
                ACTIVE_USERS_KEY,
                Double.NEGATIVE_INFINITY,
                (double) cutoff
        );

        if (removed != null && removed > 0) {
            log.info("活跃用户清理完成，移除了 {} 个超过 7 天未登录的用户", removed);
        } else {
            log.info("活跃用户清理完成，无过期数据");
        }
    }
}
