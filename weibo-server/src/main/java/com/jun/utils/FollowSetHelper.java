package com.jun.utils;

import com.jun.constant.RedisConstants;
import com.jun.mapper.FollowMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 关注/粉丝关系集合缓存（follow:following:{userId} / follow:fans:{userId}，SET of userId）的统一维护入口。
 * 数据源：user_following（我关注了谁）/ user_follower（谁关注了我）两张镜像关系表。
 * <p>
 * - TTL：重建与写入时 7 天，避免关系集合永久占用内存；
 * - 写路径（关注/取关）：SADD/SREM 前条件重建，保证返回值反映数据库真实状态——
 * key 丢失时若直接 SADD 会形成只含本次关注者的半截快照，FeedConsumer 据此推送将
 * 漏掉该用户全部其他粉丝；SREM 在 key 缺失时空转，关系永远无法从缓存移除；
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
        try {
            if (!Boolean.TRUE.equals(redisTemplate.hasKey(keyOf(type, userId)))) {
                rebuild(type, userId);
            }
        } catch (Exception e) {
            log.warn("确保关注关系缓存存在时异常: type={}, userId={}", type, userId, e);
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
     * 无锁：并发关注/取关与重建写的是同一份 DB 快照，SADD 幂等最终一致。
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
