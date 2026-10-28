package com.jun.utils;

import com.jun.constant.RedisConstants;
import com.jun.mapper.CommentsMapper;
import com.jun.vo.ReplyCountVO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 评论统计缓存（comment:stats:{commentId} Hash：replyCount、likeCount）的统一维护入口，
 * 模式与 PostStatsHelper 一致：
 * - 完整性校验：hash 必须同时含 replyCount 与 likeCount，残缺视为 miss 走重建修复，
 * 避免单字段 HINCRBY 在 key 丢失时创建残缺 hash，后续增量建立在错误基线上且无法自愈；
 * - 重建使用 SET NX PX 分布式锁 + 锁内 double-check，防止并发惊群重复重建；
 * 抢不到锁的请求短暂等待后降级读库并同样 putIfAbsent 写回（无锁安全）；
 * - 重建写回使用 putIfAbsent 逐字段补齐，不覆盖已有字段，避免丢失并发 HINCRBY 的增量；
 * - TTL：重建/回填时初始化，业务写入（点赞/回复）时续期，批量读命中时续期；
 * 数据库不维护实时基线之外的来源，likeCount 重建自 comments.likes_count（MQ 消费落库维护）。
 */
@Slf4j
@Component
public class CommentStatsHelper {

    private static final String FIELD_REPLY_COUNT = "replyCount";
    private static final String FIELD_LIKE_COUNT = "likeCount";

    private static final List<String> REQUIRED_FIELDS = List.of(FIELD_REPLY_COUNT, FIELD_LIKE_COUNT);

    /**
     * 等待他人重建的轮询参数：10 次 x 100ms，超时后降级直读数据库
     */
    private static final int WAIT_RETRY_TIMES = 10;
    private static final long WAIT_INTERVAL_MS = 100L;

    /**
     * 释放锁的 Lua 脚本：仅当锁 value 与自己写入的一致时才删除，防止误删他人的锁
     */
    private static final RedisScript<Long> UNLOCK_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    @Resource
    private RedisTemplate redisTemplate;
    @Resource
    private CommentsMapper commentsMapper;

    /**
     * 单个获取评论统计。缓存 miss（丢失或残缺）时从数据库重建；评论不存在返回 null。
     */
    public Map<Object, Object> getStats(Long commentId) {
        String statsKey = RedisConstants.COMMENT_STATS_KEY + commentId;
        Map<Object, Object> entries = redisTemplate.opsForHash().entries(statsKey);
        if (isCompleteStats(entries)) {
            redisTemplate.expire(statsKey, RedisConstants.COMMENT_STATS_TTL, TimeUnit.SECONDS);
            return entries;
        }
        return rebuild(commentId);
    }

    /**
     * 写路径防御：确保评论统计 hash 存在且字段完整，缺失时触发重建。
     * 用于 HINCRBY 之前，避免 key 丢失时 HINCRBY 创建只含单字段的残缺 hash。
     * 评论不存在时静默跳过，不影响主流程。
     */
    public void ensureStats(Long commentId) {
        if (commentId == null) {
            return;
        }
        try {
            getStats(commentId);
        } catch (Exception e) {
            log.warn("确保评论统计缓存完整时异常: commentId={}", commentId, e);
        }
    }

    /**
     * 回复数 +1/-1（先 ensure 防残缺 hash，再增量并续期），返回增量后的值
     */
    public Long incrementReply(Long commentId, long delta) {
        ensureStats(commentId);
        String statsKey = RedisConstants.COMMENT_STATS_KEY + commentId;
        Long val = redisTemplate.opsForHash().increment(statsKey, FIELD_REPLY_COUNT, delta);
        redisTemplate.expire(statsKey, RedisConstants.COMMENT_STATS_TTL, TimeUnit.SECONDS);
        return val;
    }

    /**
     * 提交后回复数增量：缓存完整时执行 HINCRBY；
     * 缓存缺失/残缺时从 DB 重建（DB 已包含本次提交的回复），不再重复应用 delta。
     */
    public void incrementReplyAfterCommit(Long commentId, long delta) {
        if (commentId == null) {
            return;
        }
        try {
            String statsKey = RedisConstants.COMMENT_STATS_KEY + commentId;
            Map<Object, Object> entries = redisTemplate.opsForHash().entries(statsKey);
            if (!isCompleteStats(entries)) {
                rebuild(commentId);
                return;
            }
            redisTemplate.opsForHash().increment(statsKey, FIELD_REPLY_COUNT, delta);
            redisTemplate.expire(statsKey, RedisConstants.COMMENT_STATS_TTL, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("提交后更新评论回复数失败: commentId={}", commentId, e);
        }
    }

    /**
     * 点赞数 +1/-1（先 ensure 防残缺 hash，再增量并续期），返回增量后的值
     */
    public Long incrementLike(Long commentId, long delta) {
        ensureStats(commentId);
        String statsKey = RedisConstants.COMMENT_STATS_KEY + commentId;
        Long val = redisTemplate.opsForHash().increment(statsKey, FIELD_LIKE_COUNT, delta);
        redisTemplate.expire(statsKey, RedisConstants.COMMENT_STATS_TTL, TimeUnit.SECONDS);
        return val;
    }

    /**
     * 读取回复数：命中续期；miss 时完整重建后返回；评论不存在返回 null
     */
    public Long getReplyCount(Long commentId) {
        return getFieldAsLong(commentId, FIELD_REPLY_COUNT);
    }

    /**
     * 读取点赞数：命中续期；miss 时完整重建后返回；评论不存在返回 null
     */
    public Long getLikeCount(Long commentId) {
        return getFieldAsLong(commentId, FIELD_LIKE_COUNT);
    }

    /**
     * 批量回填统计（putIfAbsent 两字段 + 续期），供列表 miss 兜底使用；
     * 无锁安全：并发方写入同一份 DB 快照，putIfAbsent 幂等不覆盖，
     * 若 DB 快照后于并发增量，残缺/过期字段会由下次 getStats 重建修复。
     */
    public void fillStats(Long commentId, Object replyCount, Object likeCount) {
        try {
            String statsKey = RedisConstants.COMMENT_STATS_KEY + commentId;
            if (replyCount != null) {
                redisTemplate.opsForHash().putIfAbsent(statsKey, FIELD_REPLY_COUNT, replyCount);
            }
            if (likeCount != null) {
                redisTemplate.opsForHash().putIfAbsent(statsKey, FIELD_LIKE_COUNT, likeCount);
            }
            redisTemplate.expire(statsKey, RedisConstants.COMMENT_STATS_TTL, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("回填评论统计缓存失败: commentId={}", commentId, e);
        }
    }

    /**
     * 刷新评论统计缓存 TTL
     */
    public void refreshTtl(Long commentId) {
        redisTemplate.expire(RedisConstants.COMMENT_STATS_KEY + commentId,
                RedisConstants.COMMENT_STATS_TTL, TimeUnit.SECONDS);
    }

    private Long getFieldAsLong(Long commentId, String field) {
        Map<Object, Object> stats = getStats(commentId);
        if (stats == null) {
            return null;
        }
        Object val = stats.get(field);
        return val == null ? null : Long.parseLong(val.toString());
    }

    /**
     * 缺失/残缺时的重建流程：分布式锁 + double-check + putIfAbsent 补齐。
     * 返回重建后的完整统计；评论不存在或数据库异常返回 null。
     */
    private Map<Object, Object> rebuild(Long commentId) {
        String lockKey = RedisConstants.COMMENT_STATS_LOCK_KEY + commentId;
        String lockValue = UUID.randomUUID().toString();

        Boolean locked = redisTemplate.opsForValue().setIfAbsent(
                lockKey, lockValue, Duration.ofSeconds(RedisConstants.COMMENT_STATS_LOCK_TTL_SECONDS));

        if (Boolean.TRUE.equals(locked)) {
            try {
                return doRebuild(commentId);
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
            Map<Object, Object> entries = redisTemplate.opsForHash()
                    .entries(RedisConstants.COMMENT_STATS_KEY + commentId);
            if (isCompleteStats(entries)) {
                return entries;
            }
        }

        // 等待超时（持锁方重建慢或失败）：降级读库，同样写回缓存，
        // 保证写路径（ensureStats）降级后 HINCRBY 仍作用在完整基线上，不会创建残缺 hash
        return loadFromDb(commentId);
    }

    /**
     * 持锁执行重建：double-check 防重复，putIfAbsent 补齐防覆盖并发增量
     */
    private Map<Object, Object> doRebuild(Long commentId) {
        String statsKey = RedisConstants.COMMENT_STATS_KEY + commentId;

        // double-check：可能已被其他实例重建完成
        Map<Object, Object> entries = redisTemplate.opsForHash().entries(statsKey);
        if (isCompleteStats(entries)) {
            redisTemplate.expire(statsKey, RedisConstants.COMMENT_STATS_TTL, TimeUnit.SECONDS);
            return entries;
        }

        Map<String, Object> rebuild = loadFromDbAndBuild(commentId);
        if (rebuild == null) {
            return null; // 评论不存在，不写缓存
        }

        fillMissingFields(statsKey, rebuild);
        log.info("重建评论统计缓存: commentId={}", commentId);

        // 补齐后重读，保证返回值与 Redis 实际内容一致
        Map<Object, Object> latest = redisTemplate.opsForHash().entries(statsKey);
        return isCompleteStats(latest) ? latest : new HashMap<>(rebuild);
    }

    /**
     * 逐字段 putIfAbsent 补齐缺失字段并设置 TTL。
     * 已存在字段（可能包含并发 HINCRBY 的增量）不覆盖；无锁调用也安全：
     * 并发方写入的是同一份 DB 快照，putIfAbsent 幂等不覆盖，与持锁方写回互不冲突。
     */
    private void fillMissingFields(String statsKey, Map<String, Object> rebuild) {
        for (Map.Entry<String, Object> e : rebuild.entrySet()) {
            redisTemplate.opsForHash().putIfAbsent(statsKey, e.getKey(), e.getValue());
        }
        redisTemplate.expire(statsKey, RedisConstants.COMMENT_STATS_TTL, TimeUnit.SECONDS);
    }

    /**
     * 查询数据库并构建完整统计字段；评论不存在或数据库异常返回 null（异常仅记录日志，不向业务传播）
     */
    private Map<String, Object> loadFromDbAndBuild(Long commentId) {
        try {
            List<ReplyCountVO> dbList = commentsMapper.getCommentStatsByIds(List.of(commentId));
            if (dbList.isEmpty()) {
                return null;
            }
            ReplyCountVO vo = dbList.get(0);
            Map<String, Object> map = new HashMap<>();
            map.put(FIELD_REPLY_COUNT, vo.getCount() == null ? 0 : vo.getCount());
            map.put(FIELD_LIKE_COUNT, vo.getLikesCount() == null ? 0 : vo.getLikesCount());
            return map;
        } catch (Exception e) {
            log.error("查询评论统计失败，缓存重建跳过: commentId={}", commentId, e);
            return null;
        }
    }

    /**
     * 降级路径：直读数据库构建统计，并 putIfAbsent 写回缓存修复基线。
     * 评论不存在或异常返回 null（不写缓存）。
     */
    private Map<Object, Object> loadFromDb(Long commentId) {
        Map<String, Object> rebuild = loadFromDbAndBuild(commentId);
        if (rebuild == null) {
            return null;
        }
        fillMissingFields(RedisConstants.COMMENT_STATS_KEY + commentId, rebuild);
        log.info("降级重建评论统计缓存（等待持锁方超时）: commentId={}", commentId);
        return new HashMap<>(rebuild);
    }

    /**
     * 完整性校验：hash 非空且包含全部统计字段。
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
            // 必须把 lockValue 作为 ARGV 传入，Lua 中与 KEYS[1] 的 value 比较，一致才删除
            redisTemplate.execute(UNLOCK_SCRIPT, List.of(lockKey), lockValue);
        } catch (Exception e) {
            log.warn("释放重建锁失败（等待过期自动释放）: lockKey={}", lockKey, e);
        }
    }
}
