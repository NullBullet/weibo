package com.jun.utils;

import com.jun.mapper.UsersMapper;
import com.jun.vo.UserCountVO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static com.jun.constant.RedisConstants.USER_STATS_KEY;
import static com.jun.constant.RedisConstants.USER_STATS_LOCK_KEY;
import static com.jun.constant.RedisConstants.USER_STATS_LOCK_TTL_SECONDS;
import static com.jun.constant.RedisConstants.USER_STATS_TTL;
import static java.time.Duration.ofSeconds;

/**
 * user:stats:{userId} 统计缓存的统一读取入口（与 PostStatsHelper 同一套防御模式）。
 * TTL 策略：读不续期（主页浏览等高频读不产生额外 Redis 写），
 * 仅在两种时机写入 TTL：
 * - 从可信数据源重建成功时（fillMissingFields）
 * - 统计值成功修改后由业务调用 refreshTtl 续期（关注/取关/点赞）
 * 重建使用 SET NX PX 分布式锁 + 锁内 double-check，防止并发惊群重复重建；
 * 抢不到锁的请求短暂等待后降级读库并同样 HSETNX 写回（无锁安全），保证
 * 后续 HINCRBY 一定作用在完整基线上，不阻塞业务；
 * 重建写回使用 HSETNX 逐字段补齐，不覆盖已有字段，避免丢失并发 HINCRBY 的增量
 */
@Slf4j
@Component
public class UserStatsHelper {

    public static final String FIELD_FOLLOWING_COUNT = "followingCount";
    public static final String FIELD_FOLLOWERS_COUNT = "followersCount";
    public static final String FIELD_LIKES_COUNT = "likesCount";

    private static final List<String> REQUIRED_FIELDS = List.of(
            FIELD_FOLLOWING_COUNT, FIELD_FOLLOWERS_COUNT, FIELD_LIKES_COUNT);

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
    private UsersMapper usersMapper;

    /**
     * 获取用户统计。缓存 miss（丢失或残缺）时从数据库重建。
     * 普通读取不续期 TTL，避免高频读产生额外 Redis 写；TTL 由重建与写路径负责。
     * 用户不存在返回 null。
     */
    public Map<Object, Object> getStats(Long userId) {
        String statsKey = USER_STATS_KEY + userId;
        Map<Object, Object> entries = redisTemplate.opsForHash().entries(statsKey);
        if (isCompleteStats(entries)) {
            return entries;
        }
        return rebuild(userId);
    }

    /**
     * 写路径防御：确保用户统计 hash 存在且字段完整，缺失时触发重建。
     * 用于 HINCRBY 之前，避免 key 丢失时 HINCRBY 创建只含单字段的残缺 hash。
     * 用户不存在时静默跳过，不影响主流程。
     */
    public void ensureStats(Long userId) {
        if (userId == null) {
            return;
        }
        try {
            getStats(userId);
        } catch (Exception e) {
            log.warn("确保用户统计缓存完整时异常: userId={}", userId, e);
        }
    }

    /**
     * 统计值成功修改后续期 7 天（关注/取关/点赞等写路径调用）。
     * 失败仅记录日志，不影响主流程；漏续期的 key 到期后会由重建机制自愈。
     */
    public void refreshTtl(Long userId) {
        if (userId == null) {
            return;
        }
        try {
            redisTemplate.expire(USER_STATS_KEY + userId, USER_STATS_TTL, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("用户统计缓存续期失败: userId={}", userId, e);
        }
    }

    /**
     * 缺失/残缺时的重建流程：分布式锁 + double-check + HSETNX 补齐。
     * 返回重建后的完整统计；用户不存在或数据库异常时返回 null。
     */
    private Map<Object, Object> rebuild(Long userId) {
        String lockKey = USER_STATS_LOCK_KEY + userId;
        String lockValue = UUID.randomUUID().toString();

        Boolean locked = redisTemplate.opsForValue().setIfAbsent(
                lockKey, lockValue, ofSeconds(USER_STATS_LOCK_TTL_SECONDS));

        if (Boolean.TRUE.equals(locked)) {
            try {
                return doRebuild(userId);
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
            Map<Object, Object> entries = redisTemplate.opsForHash().entries(USER_STATS_KEY + userId);
            if (isCompleteStats(entries)) {
                return entries;
            }
        }

        // 等待超时（持锁方重建慢或失败）：降级读库，同样写回缓存，
        // 保证写路径（ensureStats）降级后 HINCRBY 仍作用在完整基线上，不会创建残缺 hash
        return loadFromDb(userId);
    }

    /**
     * 持锁执行重建：double-check 防重复，HSETNX 补齐防覆盖并发增量
     */
    private Map<Object, Object> doRebuild(Long userId) {
        String statsKey = USER_STATS_KEY + userId;

        // double-check：可能已被其他实例重建完成（TTL 已由重建方设置，此处不续期）
        Map<Object, Object> entries = redisTemplate.opsForHash().entries(statsKey);
        if (isCompleteStats(entries)) {
            return entries;
        }

        Map<String, Object> rebuild = loadFromDbAndBuild(userId);
        if (rebuild == null) {
            return null; // 用户不存在，不写缓存
        }

        fillMissingFields(statsKey, rebuild);
        log.info("重建用户统计缓存: userId={}", userId);

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
        redisTemplate.expire(statsKey, USER_STATS_TTL, TimeUnit.SECONDS);
    }

    /**
     * 查询 user_statistics 并构建完整统计字段；用户不存在或数据库异常返回 null（异常仅记录日志，不向业务传播）
     */
    private Map<String, Object> loadFromDbAndBuild(Long userId) {
        try {
            UserCountVO dbStats = usersMapper.countByUserId(userId);
            if (dbStats == null) {
                return null;
            }
            Map<String, Object> map = new HashMap<>();
            map.put(FIELD_FOLLOWING_COUNT, dbStats.getFollowingCount() == null ? 0 : dbStats.getFollowingCount());
            map.put(FIELD_FOLLOWERS_COUNT, dbStats.getFollowersCount() == null ? 0 : dbStats.getFollowersCount());
            map.put(FIELD_LIKES_COUNT, dbStats.getLikesCount() == null ? 0 : dbStats.getLikesCount());
            return map;
        } catch (Exception e) {
            log.error("查询用户统计失败，缓存重建跳过: userId={}", userId, e);
            return null;
        }
    }

    /**
     * 降级路径：直读数据库构建统计，并 HSETNX 写回缓存修复基线。
     * 用户不存在或异常返回 null（不写缓存）。
     */
    private Map<Object, Object> loadFromDb(Long userId) {
        Map<String, Object> rebuild = loadFromDbAndBuild(userId);
        if (rebuild == null) {
            return null;
        }
        fillMissingFields(USER_STATS_KEY + userId, rebuild);
        return new HashMap<>(rebuild);
    }

    /**
     * 完整性校验：hash 非空且包含全部 3 个统计字段。
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
