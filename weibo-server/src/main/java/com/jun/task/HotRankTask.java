package com.jun.task;

import com.jun.utils.HotScoreCalculator;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;

import static com.jun.constant.RedisConstants.*;

@Slf4j
@Component
public class HotRankTask {

    @Resource
    private RedisTemplate redisTemplate;

    @Scheduled(cron = "0 */5 * * * *")
    public void refreshHotRank() {
        log.info("定时更新热门推荐：{}", LocalDateTime.now());
        Set<Object> members = redisTemplate.opsForSet().members(HOT_POST_CANDIDATE_KEY);

        if (members == null || members.isEmpty()) {
            return;
        }

        for (Object member : members) {

            String postId = member.toString();

            String statsKey = POST_STATS_KEY + postId;

            Map<Object, Object> entries = redisTemplate.opsForHash().entries(statsKey);

            if (entries == null || entries.isEmpty()) {
                // post:stats 不存在 = 帖子已被软删除 → 从热点榜中清理
                redisTemplate.opsForSet().remove(HOT_POST_CANDIDATE_KEY, postId);
                redisTemplate.opsForZSet().remove(HOT_POST_ZSET_KEY, postId);
                continue;
            }

            Long likeCount = getLong(entries, "likeCount");

            Long commentCount = getLong(entries, "commentCount");

            Long forwardCount = getLong(entries, "forwardCount");

            Long viewCount = getLong(entries, "viewCount");

            Long createTime = getLong(entries, "createTime");

            double score = HotScoreCalculator.calculate(viewCount, likeCount, commentCount, forwardCount, createTime);

            redisTemplate.opsForZSet().add(HOT_POST_ZSET_KEY, postId, score);

            // 清理冷帖子
            if (score <= 0) {
                redisTemplate.opsForSet().remove(HOT_POST_CANDIDATE_KEY, postId);
                redisTemplate.opsForZSet().remove(HOT_POST_ZSET_KEY, postId);
            }
        }
    }

    private Long getLong(Map<Object, Object> map, String key) {

        Object value = map.get(key);

        return value == null ? 0L : Long.parseLong(value.toString());
    }
}
