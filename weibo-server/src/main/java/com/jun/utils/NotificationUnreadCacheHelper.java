package com.jun.utils;

import com.jun.constant.RedisConstants;
import com.jun.mapper.MessageMapper;
import com.jun.mapper.NotificationsMapper;
import com.jun.mapper.RewardActivityMapper;
import com.jun.vo.UnreadCountVO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 消息中心统一未读数旁路缓存（chat / comment / like / system）的统一读取与刷新入口。
 * 缓存结构：notification:unread:{userId} -> Hash{chat, comment, like, system}。
 * <p>
 * - 数据源 MySQL：conversation_user、notification_comment、notification_like、notification_system；
 * - TTL 7 天：读取命中与未读数变化（新私信、新通知、标记已读）时续期，活跃用户的缓存不会被 TTL 淘汰；
 * - 缓存失效（key 丢失或字段残缺）时按 MySQL 重建，读路径 miss 也能自助恢复；
 * - 重建/刷新使用 SET NX PX 分布式锁 + 锁内 double-check，避免并发惊群重复查库，
 *   并让"读库-写缓存"在锁内串行，防止旧快照覆盖新快照；抢不到锁的请求短暂等待后降级直读 MySQL。
 */
@Slf4j
@Component
public class NotificationUnreadCacheHelper {

    private static final String FIELD_CHAT = "chat";
    private static final String FIELD_COMMENT = "comment";
    private static final String FIELD_LIKE = "like";
    private static final String FIELD_SYSTEM = "system";

    private static final List<String> REQUIRED_FIELDS = List.of(
            FIELD_CHAT, FIELD_COMMENT, FIELD_LIKE, FIELD_SYSTEM);

    /**
     * 等待他人重建/刷新的轮询参数：10 次 x 100ms，超时后降级直读数据库
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
    private MessageMapper messageMapper;
    @Resource
    private NotificationsMapper notificationsMapper;
    @Resource
    private RewardActivityMapper rewardActivityMapper;

    /**
     * 读取统一未读数：缓存命中直接返回；key 缺失/字段残缺/Redis 异常时按 MySQL 重建或降级直读。
     */
    public UnreadCountVO get(Long userId) {
        if (userId == null) {
            return zeroCounts();
        }
        try {
            return load(userId, false);
        } catch (Exception e) {
            log.warn("读取统一未读数缓存失败，降级直读数据库: userId={}", userId, e);
            return loadFromDb(userId);
        }
    }

    /**
     * 未读数发生变化后调用：按 MySQL 重算全部字段并写回缓存、续期 TTL。
     * 事务提交后的调用请使用 {@link #refreshAfterCommit(Long)}，避免把未提交的计数写入缓存。
     */
    public UnreadCountVO refresh(Long userId) {
        if (userId == null) {
            return zeroCounts();
        }
        try {
            return load(userId, true);
        } catch (Exception e) {
            log.warn("刷新统一未读数缓存失败，降级直读数据库: userId={}", userId, e);
            // 刷新失败时删除旧快照，避免残留的旧未读数在 TTL 内持续被读取
            deleteQuietly(RedisConstants.NOTIFICATION_UNREAD_KEY + userId);
            return loadFromDb(userId);
        }
    }

    /**
     * 未读数变化后按事务提交时机刷新缓存；无活动事务时直接刷新。异常仅记录日志，不影响业务主流程。
     */
    public void refreshAfterCommit(Long userId) {
        if (userId == null) {
            return;
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            refresh(userId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                refresh(userId);
            }
        });
    }

    /**
     * 锁内完成"读库 + 写缓存"。
     * forceReload=true 表示未读数已变化，忽略已有缓存并按 MySQL 重算；false 表示读路径，命中即返回。
     */
    private UnreadCountVO load(Long userId, boolean forceReload) {
        String key = RedisConstants.NOTIFICATION_UNREAD_KEY + userId;
        String lockKey = RedisConstants.NOTIFICATION_UNREAD_LOCK_KEY + userId;

        for (int attempt = 0; attempt <= WAIT_RETRY_TIMES; attempt++) {
            if (!forceReload) {
                Map<Object, Object> entries = redisTemplate.opsForHash().entries(key);
                if (isComplete(entries)) {
                    // 命中后续期：活跃用户的未读数不会因 TTL 到期被淘汰
                    refreshTtl(key);
                    return toVO(entries);
                }
            }

            String lockValue = UUID.randomUUID().toString();
            Boolean locked = redisTemplate.opsForValue().setIfAbsent(
                    lockKey, lockValue, Duration.ofSeconds(RedisConstants.NOTIFICATION_UNREAD_LOCK_TTL_SECONDS));

            if (Boolean.TRUE.equals(locked)) {
                try {
                    // double-check：读路径可能已被其他实例重建完成
                    if (!forceReload) {
                        Map<Object, Object> latest = redisTemplate.opsForHash().entries(key);
                        if (isComplete(latest)) {
                            return toVO(latest);
                        }
                    }
                    UnreadCountVO counts = loadFromDb(userId);
                    write(key, counts);
                    if (forceReload) {
                        log.debug("刷新统一未读数缓存: userId={}", userId);
                    } else {
                        log.info("重建统一未读数缓存: userId={}", userId);
                    }
                    return counts;
                } finally {
                    releaseLock(lockKey, lockValue);
                }
            }

            try {
                Thread.sleep(WAIT_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        // 等待超时：删除可能残留的旧快照并直读 MySQL，不写缓存，由下一次读取或未读数变化事件重建
        log.warn("等待统一未读数缓存重建超时，降级直读数据库: userId={}", userId);
        deleteQuietly(key);
        return loadFromDb(userId);
    }

    /**
     * 从 MySQL 汇总四类未读数（数据源，不经过任何缓存）
     */
    private UnreadCountVO loadFromDb(Long userId) {
        UnreadCountVO counts = new UnreadCountVO();
        counts.setChat(countOrZero(messageMapper.countUnreadMessages(userId)));
        counts.setComment(countOrZero(notificationsMapper.countUnreadCommentNotifications(userId)));
        counts.setLike(countOrZero(notificationsMapper.countUnreadLikeNotifications(userId)));
        counts.setSystem(countOrZero(rewardActivityMapper.countUnreadSystemNotifications(userId)));
        return counts;
    }

    /**
     * 整体覆盖写入四个字段并设置 TTL：未读数以 MySQL 为准，不保留旧字段值与旧 TTL
     */
    private void write(String key, UnreadCountVO counts) {
        Map<String, Object> map = new HashMap<>();
        map.put(FIELD_CHAT, counts.getChat());
        map.put(FIELD_COMMENT, counts.getComment());
        map.put(FIELD_LIKE, counts.getLike());
        map.put(FIELD_SYSTEM, counts.getSystem());
        redisTemplate.opsForHash().putAll(key, map);
        redisTemplate.expire(key, RedisConstants.NOTIFICATION_UNREAD_TTL, TimeUnit.SECONDS);
    }

    /**
     * 完整性校验：Hash 必须包含全部 4 个未读数字段，残缺视为 miss 走重建修复
     */
    private boolean isComplete(Map<?, ?> entries) {
        if (entries == null || entries.isEmpty()) {
            return false;
        }
        for (String field : REQUIRED_FIELDS) {
            if (!entries.containsKey(field)) {
                return false;
            }
        }
        return true;
    }

    private UnreadCountVO toVO(Map<?, ?> entries) {
        UnreadCountVO vo = new UnreadCountVO();
        vo.setChat(parseCount(entries.get(FIELD_CHAT)));
        vo.setComment(parseCount(entries.get(FIELD_COMMENT)));
        vo.setLike(parseCount(entries.get(FIELD_LIKE)));
        vo.setSystem(parseCount(entries.get(FIELD_SYSTEM)));
        return vo;
    }

    /**
     * 兼容 JSON 反序列化后的 Number 与字符串两种数值形态
     */
    private long parseCount(Object value) {
        if (value == null) {
            return 0L;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(value.toString());
    }

    private UnreadCountVO zeroCounts() {
        UnreadCountVO counts = new UnreadCountVO();
        counts.setChat(0L);
        counts.setComment(0L);
        counts.setLike(0L);
        counts.setSystem(0L);
        return counts;
    }

    private long countOrZero(Long count) {
        return count == null ? 0L : count;
    }

    /**
     * 命中缓存后续期 7 天：活跃用户每次读取都会延长缓存寿命，避免被 TTL 淘汰
     */
    private void refreshTtl(String key) {
        try {
            redisTemplate.expire(key, RedisConstants.NOTIFICATION_UNREAD_TTL, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("续期统一未读数缓存失败: key={}", key, e);
        }
    }

    private void releaseLock(String lockKey, String lockValue) {
        try {
            redisTemplate.execute(UNLOCK_SCRIPT, List.of(lockKey), lockValue);
        } catch (Exception e) {
            log.warn("释放统一未读数重建锁失败（等待过期自动释放）: lockKey={}", lockKey, e);
        }
    }

    private void deleteQuietly(String key) {
        try {
            redisTemplate.delete(key);
        } catch (Exception e) {
            log.warn("删除统一未读数缓存失败: key={}", key, e);
        }
    }
}
