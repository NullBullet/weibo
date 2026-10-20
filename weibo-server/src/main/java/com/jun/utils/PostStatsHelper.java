package com.jun.utils;

import com.jun.entity.Statuses;
import com.jun.mapper.StatusesMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.jun.constant.RedisConstants.POST_STATS_KEY;
import static com.jun.constant.RedisConstants.POST_STATS_TTL;

/**
 * post:stats:{postId} 统计缓存的统一读取入口：
 * - 读取命中时刷新 TTL（活跃帖子不过期，冷数据 7 天后自动过期回收）
 * - 读取 miss 时从数据库重建缓存（重建后同样写入 TTL），避免 key 丢失导致业务不可用
 * <p>
 * 注意：数据库不维护 views_count，重建后浏览量从 0 开始计数
 */
@Slf4j
@Component
public class PostStatsHelper {

    @Resource
    private RedisTemplate redisTemplate;
    @Resource
    private StatusesMapper statusesMapper;

    /**
     * 批量获取帖子统计，返回结果与 ids 等长且顺序一致。
     * 缓存 miss 的帖子从数据库重建；数据库中也不存在的帖子对应位置为 null。
     */
    public List<Map<Object, Object>> getStatsBatch(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Collections.emptyList();
        }

        // 1. 批量读缓存
        List<Object> cached = redisTemplate.executePipelined((org.springframework.data.redis.core.RedisCallback<Object>) connection -> {
            for (Long id : ids) {
                connection.hashCommands().hGetAll((POST_STATS_KEY + id).getBytes());
            }
            return null;
        });

        List<Map<Object, Object>> result = new ArrayList<>(ids.size());
        List<Long> missIds = new ArrayList<>();
        List<Integer> missIndex = new ArrayList<>();
        List<Long> hitIds = new ArrayList<>();
        for (int i = 0; i < ids.size(); i++) {
            Object obj = (cached != null && i < cached.size()) ? cached.get(i) : null;
            if (obj instanceof Map<?, ?> stats && !stats.isEmpty()) {
                result.add((Map<Object, Object>) stats);
                hitIds.add(ids.get(i));
            } else {
                result.add(null);
                missIds.add(ids.get(i));
                missIndex.add(i);
            }
        }

        // 2. 命中的 key 刷新 TTL
        if (!hitIds.isEmpty()) {
            redisTemplate.executePipelined((org.springframework.data.redis.core.RedisCallback<Object>) connection -> {
                for (Long id : hitIds) {
                    connection.keyCommands().expire(
                            (POST_STATS_KEY + id).getBytes(), POST_STATS_TTL);
                }
                return null;
            });
        }

        // 3. miss 的 key 从数据库重建
        if (!missIds.isEmpty()) {
            Map<Long, Statuses> dbMap = statusesMapper.selectStatsByIds(missIds).stream()
                    .collect(java.util.stream.Collectors.toMap(Statuses::getId, s -> s));
            for (int j = 0; j < missIds.size(); j++) {
                Long id = missIds.get(j);
                Statuses s = dbMap.get(id);
                if (s == null) {
                    continue; // 帖子不存在（已删除），保持 null
                }
                Map<String, Object> rebuild = buildStatsMap(s);
                writeRebuild(id, rebuild);
                result.set(missIndex.get(j), new HashMap<>(rebuild));
            }
        }

        return result;
    }

    /**
     * 单个获取帖子统计。缓存 miss 时从数据库重建；帖子不存在返回 null。
     */
    public Map<Object, Object> getStats(Long id) {
        String statsKey = POST_STATS_KEY + id;
        Map<Object, Object> entries = redisTemplate.opsForHash().entries(statsKey);
        if (entries != null && !entries.isEmpty()) {
            redisTemplate.expire(statsKey, POST_STATS_TTL, TimeUnit.SECONDS);
            return entries;
        }

        List<Statuses> dbList = statusesMapper.selectStatsByIds(List.of(id));
        if (dbList.isEmpty()) {
            return null;
        }
        Map<String, Object> rebuild = buildStatsMap(dbList.get(0));
        writeRebuild(id, rebuild);
        return new HashMap<>(rebuild);
    }

    private void writeRebuild(Long id, Map<String, Object> rebuild) {
        String statsKey = POST_STATS_KEY + id;
        redisTemplate.opsForHash().putAll(statsKey, rebuild);
        redisTemplate.expire(statsKey, POST_STATS_TTL, TimeUnit.SECONDS);
        log.info("重建帖子统计缓存: postId={}", id);
    }

    private Map<String, Object> buildStatsMap(Statuses s) {
        Map<String, Object> map = new HashMap<>();
        map.put("likeCount", s.getAttitudesCount() == null ? 0 : s.getAttitudesCount());
        map.put("commentCount", s.getCommentsCount() == null ? 0 : s.getCommentsCount());
        map.put("forwardCount", s.getRepostsCount() == null ? 0 : s.getRepostsCount());
        map.put("viewCount", s.getViewsCount() == null ? 0 : s.getViewsCount());
        // 与初始化处保持一致，使用 UTC epoch 秒
        map.put("createTime", s.getCreatedAt() == null ? 0L
                : s.getCreatedAt().toEpochSecond(ZoneOffset.UTC));
        return map;
    }

    /**
     * 从统计 Map 中读取 int 字段（兼容 Number 与字符串两种反序列化结果）
     */
    public static int getInt(Map<?, ?> stats, String field) {
        Object val = stats.get(field);
        if (val == null) {
            return 0;
        }
        return val instanceof Number n ? n.intValue() : Integer.parseInt(val.toString());
    }
}
