package com.jun.utils;

import com.jun.entity.Statuses;
import com.jun.mapper.StatusesMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static com.jun.constant.RedisConstants.POST_STATS_KEY;
import static com.jun.constant.RedisConstants.POST_STATS_LOCK_KEY;
import static com.jun.constant.RedisConstants.POST_STATS_LOCK_TTL_SECONDS;
import static com.jun.constant.RedisConstants.POST_STATS_TTL;
import static java.time.Duration.ofSeconds;

/**
 * post:stats:{postId} 统计缓存的统一读取入口：
 * - 读取命中且字段完整时刷新 TTL（活跃帖子不过期，冷数据 7 天后自动过期回收）
 * - 读取 miss（key 丢失或 hash 残缺）时从数据库重建缓存，避免 key 丢失导致业务不可用
 * - 重建使用 SET NX PX 分布式锁 + 锁内 double-check，防止并发惊群重复重建；
 *   抢不到锁的请求短暂等待后降级读库并同样 HSETNX 写回（无锁安全），保证
 *   后续 HINCRBY 一定作用在完整基线上，不阻塞业务
 * - 重建写回使用 HSETNX 逐字段补齐，不覆盖已有字段，避免丢失并发 HINCRBY 的增量
 * <p>
 * 注意：数据库不维护 views_count，重建后浏览量从 0 开始计数
 */
@Slf4j
@Component
public class PostStatsHelper {

    private static final String FIELD_LIKE_COUNT = "likeCount";
    private static final String FIELD_COMMENT_COUNT = "commentCount";
    private static final String FIELD_FORWARD_COUNT = "forwardCount";
    private static final String FIELD_VIEW_COUNT = "viewCount";
    private static final String FIELD_CREATE_TIME = "createTime";

    private static final List<String> REQUIRED_FIELDS = List.of(
            FIELD_LIKE_COUNT, FIELD_COMMENT_COUNT, FIELD_FORWARD_COUNT,
            FIELD_VIEW_COUNT, FIELD_CREATE_TIME);

    /** 等待他人重建的轮询参数：10 次 x 100ms，超时后降级直读数据库 */
    private static final int WAIT_RETRY_TIMES = 10;
    private static final long WAIT_INTERVAL_MS = 100L;

    /** 释放锁的 Lua 脚本：仅当锁 value 与自己写入的一致时才删除，防止误删他人的锁 */
    private static final RedisScript<Long> UNLOCK_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    @Resource
    private RedisTemplate redisTemplate;
    @Resource
    private StatusesMapper statusesMapper;

    /**
     * 批量获取帖子统计，返回结果与 ids 等长且顺序一致。
     * 缓存 miss（丢失或残缺）的帖子从数据库重建；数据库中也不存在的帖子对应位置为 null。
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
            Map<?, ?> stats = obj instanceof Map<?, ?> m ? m : null;
            if (isCompleteStats(stats)) {
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

        // 3. miss 的 key 逐个走锁内重建（丢失或残缺）
        for (int j = 0; j < missIds.size(); j++) {
            result.set(missIndex.get(j), getStats(missIds.get(j)));
        }

        return result;
    }

    /**
     * 单个获取帖子统计。缓存 miss（丢失或残缺）时从数据库重建；帖子不存在返回 null。
     */
    public Map<Object, Object> getStats(Long id) {
        String statsKey = POST_STATS_KEY + id;
        Map<Object, Object> entries = redisTemplate.opsForHash().entries(statsKey);
        if (isCompleteStats(entries)) {
            redisTemplate.expire(statsKey, POST_STATS_TTL, TimeUnit.SECONDS);
            return entries;
        }
        return rebuild(id);
    }

    /**
     * 写路径防御：确保帖子统计 hash 存在且字段完整，缺失时触发重建。
     * 用于 HINCRBY 之前，避免 key 丢失时 HINCRBY 创建只含单字段的残缺 hash。
     * 帖子不存在时静默跳过，不影响主流程。
     */
    public void ensureStats(Long id) {
        if (id == null) {
            return;
        }
        try {
            getStats(id);
        } catch (Exception e) {
            log.warn("确保帖子统计缓存完整时异常: postId={}", id, e);
        }
    }

    /**
     * 缺失/残缺时的重建流程：分布式锁 + double-check + HSETNX 补齐。
     * 返回重建后的完整统计；帖子不存在或数据库异常时返回 null。
     */
    private Map<Object, Object> rebuild(Long id) {
        String lockKey = POST_STATS_LOCK_KEY + id;
        String lockValue = UUID.randomUUID().toString();

        Boolean locked = redisTemplate.opsForValue().setIfAbsent(
                lockKey, lockValue, ofSeconds(POST_STATS_LOCK_TTL_SECONDS));

        if (Boolean.TRUE.equals(locked)) {
            try {
                return doRebuild(id);
            } finally {
                releaseLock(lockKey, lockValue);
            }
        }

        // 未抢到锁：等待持锁方完成重建，轮询读缓存
        for (int i = 0; i < WAIT_RETRY_TIMES; i++) {
            try {
                Thread.sleep(WAIT_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            Map<Object, Object> entries = redisTemplate.opsForHash().entries(POST_STATS_KEY + id);
            if (isCompleteStats(entries)) {
                return entries;
            }
        }

        // 等待超时（持锁方重建慢或失败）：降级读库，同样写回缓存，
        // 保证写路径（ensureStats）降级后 HINCRBY 仍作用在完整基线上，不会创建残缺 hash
        return loadFromDb(id);
    }

    /**
     * 持锁执行重建：double-check 防重复，HSETNX 补齐防覆盖并发增量
     */
    private Map<Object, Object> doRebuild(Long id) {
        String statsKey = POST_STATS_KEY + id;

        // double-check：可能已被其他实例重建完成
        Map<Object, Object> entries = redisTemplate.opsForHash().entries(statsKey);
        if (isCompleteStats(entries)) {
            redisTemplate.expire(statsKey, POST_STATS_TTL, TimeUnit.SECONDS);
            return entries;
        }

        Map<String, Object> rebuild = loadFromDbAndBuild(id);
        if (rebuild == null) {
            return null; // 帖子不存在，不写缓存
        }

        fillMissingFields(statsKey, rebuild);
        log.info("重建帖子统计缓存: postId={}", id);

        // 补齐后重读，保证返回值与 Redis 实际内容一致
        Map<Object, Object> latest = redisTemplate.opsForHash().entries(statsKey);
        return isCompleteStats(latest) ? latest : new HashMap<>(rebuild);
    }

    /**
     * 逐字段 HSETNX 补齐缺失字段并设置 TTL。
     * 已存在字段（可能包含并发 HINCRBY 的增量）不覆盖；无锁调用也安全：
     * 并发方写入的是同一份 DB 快照，HSETNX 幂等不覆盖，与持锁方写回互不冲突。
     */
    private void fillMissingFields(String statsKey, Map<String, Object> rebuild) {
        for (Map.Entry<String, Object> e : rebuild.entrySet()) {
            redisTemplate.opsForHash().putIfAbsent(statsKey, e.getKey(), e.getValue());
        }
        redisTemplate.expire(statsKey, POST_STATS_TTL, TimeUnit.SECONDS);
    }

    /**
     * 查询数据库并构建完整统计字段；帖子不存在或数据库异常返回 null（异常仅记录日志，不向业务传播）
     */
    private Map<String, Object> loadFromDbAndBuild(Long id) {
        try {
            List<Statuses> dbList = statusesMapper.selectStatsByIds(List.of(id));
            if (dbList.isEmpty()) {
                return null;
            }
            return buildStatsMap(dbList.get(0));
        } catch (Exception e) {
            log.error("查询帖子统计失败，缓存重建跳过: postId={}", id, e);
            return null;
        }
    }

    /**
     * 降级路径：直读数据库构建统计，并 HSETNX 写回缓存修复基线。
     * 帖子不存在或异常返回 null（不写缓存）。
     */
    private Map<Object, Object> loadFromDb(Long id) {
        Map<String, Object> rebuild = loadFromDbAndBuild(id);
        if (rebuild == null) {
            return null;
        }
        fillMissingFields(POST_STATS_KEY + id, rebuild);
        return new HashMap<>(rebuild);
    }

    /**
     * 完整性校验：hash 非空且包含全部 5 个统计字段。
     * key 丢失时 HINCRBY 等写操作会产生只含单字段的残缺 hash，残缺视为 miss 走重建修复。
     */
    private boolean isCompleteStats(Map<?, ?> stats) {
        if (stats == null || stats.isEmpty()) {
            return false;
        }
        for (String field : REQUIRED_FIELDS) {
            if (!stats.containsKey(field)) {
                return false;
            }
        }
        return true;
    }

    private void releaseLock(String lockKey, String lockValue) {
        try {
            redisTemplate.execute(UNLOCK_SCRIPT, Collections.singletonList(lockKey), lockValue);
        } catch (Exception e) {
            log.warn("释放重建锁失败（等待过期自动释放）: lockKey={}", lockKey, e);
        }
    }

    private Map<String, Object> buildStatsMap(Statuses s) {
        Map<String, Object> map = new HashMap<>();
        map.put(FIELD_LIKE_COUNT, s.getAttitudesCount() == null ? 0 : s.getAttitudesCount());
        map.put(FIELD_COMMENT_COUNT, s.getCommentsCount() == null ? 0 : s.getCommentsCount());
        map.put(FIELD_FORWARD_COUNT, s.getRepostsCount() == null ? 0 : s.getRepostsCount());
        map.put(FIELD_VIEW_COUNT, s.getViewsCount() == null ? 0 : s.getViewsCount());
        // 与初始化处保持一致，使用 UTC epoch 秒
        map.put(FIELD_CREATE_TIME, s.getCreatedAt() == null ? 0L
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
