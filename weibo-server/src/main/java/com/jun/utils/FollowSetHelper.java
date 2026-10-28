package com.jun.utils;

import com.jun.constant.RedisConstants;
import com.jun.mapper.FollowMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 关注/粉丝关系集合缓存（follow:following:{userId} / follow:fans:{userId}，SET of userId）的统一维护入口。
 * 数据源：user_following（我关注了谁）/ user_follower（谁关注了我）两张镜像关系表。
 * <p>
 * - TTL：重建与写入时 7 天，避免关系集合永久占用内存；
 * - 写路径（关注/取关）：数据库事务提交后执行 SADD/SREM；提交前由业务调用 ensure*
 *   条件重建，避免 key 丢失时直接 SADD 形成只含本次关注者的半截快照，或 SREM 空转；
 * - 读路径（isFollowed 展示、粉丝推送）：miss 时从数据库重建兜底，
 * FOLLOW_FANS_KEY 丢失会导致粉丝全部收不到新微博（消息仍被 ACK，无补偿机会）；
 * - 空集合哨兵：无关注/无粉丝时不落真实成员，仅写哨兵成员 "0" 占位使 key 存在并带 TTL，
 * 避免每次访问都触发重建查库；userId 恒为正整数，哨兵不影响 isMember/contains 判断，
 * 唯一需要跳过哨兵的是遍历推送收件箱的 FeedConsumer。
 */
@Slf4j
@Component
public class FollowSetHelper {

    /**
     * 空集合哨兵成员：userId 恒为正整数，"0" 不会与真实用户冲突
     */
    public static final String EMPTY_SENTINEL = "0";

    /**
     * 释放锁的 Lua 脚本：仅当锁 value 与自己写入的一致时才删除，防止误删他人的锁
     */
    private static final RedisScript<Long> UNLOCK_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    /**
     * 等待他人重建的轮询参数：10 次 x 100ms，超时后跳过本次重建
     */
    private static final int WAIT_RETRY_TIMES = 10;
    private static final long WAIT_INTERVAL_MS = 100L;

    @Resource
    private RedisTemplate redisTemplate;
    @Resource
    private FollowMapper followMapper;

    private String keyOf(Type type, Long userId) {
        return (type == Type.FOLLOWING
                ? RedisConstants.FOLLOW_FOLLOWING_KEY
                : RedisConstants.FOLLOW_FANS_KEY) + userId;
    }

    /**
     * 写/读路径：确保「我关注了谁」集合存在（key 缺失时从 user_following 重建）。
     * 写路径须在任何 DB 写之前调用；异常仅记录日志不传播，失败时退化为原行为。
     */
    public void ensureFollowing(Long userId) {
        ensure(Type.FOLLOWING, userId);
    }

    /**
     * 写/读路径：确保「谁关注了我」集合存在（key 缺失时从 user_follower 重建）。
     * 写路径须在任何 DB 写之前调用；异常仅记录日志不传播。
     */
    public void ensureFans(Long userId) {
        ensure(Type.FANS, userId);
    }

    private void ensure(Type type, Long userId) {
        if (userId == null) {
            return;
        }
        String cacheKey = keyOf(type, userId);
        try {
            if (Boolean.TRUE.equals(redisTemplate.hasKey(cacheKey))) {
                return;
            }

            String typeKey = type == Type.FOLLOWING ? "following" : "fans";
            String lockKey = RedisConstants.FOLLOW_SET_LOCK_KEY + typeKey + ":" + userId;
            String lockValue = UUID.randomUUID().toString();
            Boolean locked = redisTemplate.opsForValue().setIfAbsent(
                    lockKey, lockValue, RedisConstants.FOLLOW_SET_LOCK_TTL_SECONDS, TimeUnit.SECONDS);

            if (Boolean.TRUE.equals(locked)) {
                try {
                    // double-check：可能已被其他实例重建完成
                    if (!Boolean.TRUE.equals(redisTemplate.hasKey(cacheKey))) {
                        rebuild(type, userId);
                    }
                } finally {
                    releaseLock(lockKey, lockValue);
                }
                return;
            }

            // 未抢到锁：等待持锁方完成，避免重复重建
            for (int i = 0; i < WAIT_RETRY_TIMES; i++) {
                Thread.sleep(WAIT_INTERVAL_MS);
                if (Boolean.TRUE.equals(redisTemplate.hasKey(cacheKey))) {
                    return;
                }
            }
            log.warn("等待关注关系缓存重建超时，跳过本次重建: type={}, userId={}", type, userId);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("等待关注关系缓存重建被中断: type={}, userId={}", type, userId);
        } catch (Exception e) {
            log.warn("确保关注关系缓存存在时异常: type={}, userId={}", type, userId, e);
        }
    }

    private void releaseLock(String lockKey, String lockValue) {
        try {
            redisTemplate.execute(UNLOCK_SCRIPT, Collections.singletonList(lockKey), lockValue);
        } catch (Exception e) {
            log.warn("释放关注关系重建锁失败（等待过期自动释放）: lockKey={}", lockKey, e);
        }
    }

    /**
     * 写路径：关注/取关成功后续期，活跃用户的关系集合不过期
     */
    public void refreshTtlFollowing(Long userId) {
        refreshTtl(Type.FOLLOWING, userId);
    }

    /**
     * 写路径：被关注/被取关成功后续期
     */
    public void refreshTtlFans(Long userId) {
        refreshTtl(Type.FANS, userId);
    }

    private void refreshTtl(Type type, Long userId) {
        if (userId == null) {
            return;
        }
        try {
            redisTemplate.expire(keyOf(type, userId), RedisConstants.FOLLOW_SET_TTL, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("续期关注关系缓存时异常: type={}, userId={}", type, userId, e);
        }
    }

    /**
     * 从数据库重建单个用户的关系集合：查询关系表后 SADD 写回（哨兵占位 + TTL）。
     * 重建入口由 ensure 方法用 SET NX PX 锁 + double-check 保护，避免并发重复重建；
     * 后续关注/取关的缓存维护是 SADD/SREM 原子操作，不做整键覆盖。
     */
    private void rebuild(Type type, Long userId) {
        List<Long> members = type == Type.FOLLOWING
                ? followMapper.getFollowingIds(userId)
                : followMapper.getFansIds(userId);

        byte[] key = keyOf(type, userId).getBytes();
        redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
            // 哨兵占位：即使空集合也让 key 存在并带 TTL，避免反复重建查库
            connection.setCommands().sAdd(key, EMPTY_SENTINEL.getBytes());
            if (members != null && !members.isEmpty()) {
                byte[][] values = new byte[members.size()][];
                int i = 0;
                for (Long member : members) {
                    values[i++] = member.toString().getBytes();
                }
                connection.setCommands().sAdd(key, values);
            }
            connection.keyCommands().expire(key, RedisConstants.FOLLOW_SET_TTL);
            return null;
        });
        log.info("重建关注关系缓存: type={}, userId={}, count={}",
                type, userId, members == null ? 0 : members.size());
    }

    private enum Type {
        /**
         * 我关注了谁，数据源 user_following
         */
        FOLLOWING,
        /**
         * 谁关注了我，数据源 user_follower
         */
        FANS
    }
}
