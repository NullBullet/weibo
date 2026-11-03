package com.jun.utils;

import cn.hutool.json.JSONUtil;
import com.jun.constant.RedisConstants;
import com.jun.vo.LikeNotificationPageVO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 点赞聚合通知分页旁路缓存：
 * notification:like:{receiverId}:{cursorTime}:{cursorId}:{size}
 * <p>
 * - TTL 固定 30 秒，命中不续期；
 * - notification:like:{receiverId}:pages 是页索引，仅用于失效时批量删除已缓存分页；
 * - 点赞通知消费者在数据库事务提交后按 receiverId 删除全部分页缓存，TTL 作为兜底。
 */
@Slf4j
@Component
public class NotificationLikeCacheHelper {

    private static final String FIRST_PAGE = "first";

    @Resource
    private RedisTemplate redisTemplate;

    /**
     * 读取分页缓存，未命中或反序列化失败返回 null。
     */
    public LikeNotificationPageVO get(Long receiverId, LocalDateTime cursorTime, Integer cursorId, int size) {
        if (receiverId == null) {
            return null;
        }
        String key = buildPageKey(receiverId, cursorTime, cursorId, size);
        try {
            Object json = redisTemplate.opsForValue().get(key);
            if (json == null) {
                return null;
            }
            return JSONUtil.toBean(json.toString(), LikeNotificationPageVO.class);
        } catch (Exception e) {
            log.warn("读取点赞通知分页缓存失败，key={}", key, e);
            deleteQuietly(key);
            return null;
        }
    }

    /**
     * 写入分页缓存并登记页索引。第一页使用 first:first:{size} 作为游标占位。
     */
    public void put(Long receiverId, LocalDateTime cursorTime, Integer cursorId, int size,
                    LikeNotificationPageVO page) {
        if (receiverId == null || page == null) {
            return;
        }
        String key = buildPageKey(receiverId, cursorTime, cursorId, size);
        String pagesKey = buildPagesKey(receiverId);
        try {
            redisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(page),
                    RedisConstants.NOTIFICATION_LIKE_CACHE_TTL, TimeUnit.SECONDS);
            redisTemplate.opsForSet().add(pagesKey, buildPageSuffix(cursorTime, cursorId, size));
            redisTemplate.expire(pagesKey, RedisConstants.NOTIFICATION_LIKE_PAGES_TTL, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("写入点赞通知分页缓存失败，key={}", key, e);
            deleteQuietly(key);
        }
    }

    /**
     * 在事务提交后删除缓存；没有活动事务时直接删除。
     */
    public void evictAfterCommit(Long receiverId) {
        if (receiverId == null) {
            return;
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            evict(receiverId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                evict(receiverId);
            }
        });
    }

    /**
     * 删除指定用户所有已缓存分页；页索引丢失时兜底删除固定的第一页 key。
     */
    public void evict(Long receiverId) {
        if (receiverId == null) {
            return;
        }
        String pagesKey = buildPagesKey(receiverId);
        try {
            Set<Object> suffixes = redisTemplate.opsForSet().members(pagesKey);
            if (suffixes != null) {
                for (Object suffix : suffixes) {
                    redisTemplate.delete(RedisConstants.NOTIFICATION_LIKE_CACHE_KEY
                            + receiverId + ":" + suffix);
                }
            }
            redisTemplate.delete(pagesKey);
            redisTemplate.delete(buildPageKey(receiverId, null, null,
                    RedisConstants.NOTIFICATION_LIKE_PAGE_SIZE));
        } catch (Exception e) {
            log.warn("删除点赞通知分页缓存失败，receiverId={}", receiverId, e);
        }
    }

    private String buildPageKey(Long receiverId, LocalDateTime cursorTime, Integer cursorId, int size) {
        return RedisConstants.NOTIFICATION_LIKE_CACHE_KEY + receiverId + ":"
                + buildPageSuffix(cursorTime, cursorId, size);
    }

    private String buildPagesKey(Long receiverId) {
        return RedisConstants.NOTIFICATION_LIKE_CACHE_KEY + receiverId + ":pages";
    }

    private String buildPageSuffix(LocalDateTime cursorTime, Integer cursorId, int size) {
        String timePart = cursorTime == null ? FIRST_PAGE : cursorTime.toString();
        String idPart = cursorId == null ? FIRST_PAGE : String.valueOf(cursorId);
        return timePart + ":" + idPart + ":" + size;
    }

    private void deleteQuietly(String key) {
        try {
            redisTemplate.delete(key);
        } catch (Exception e) {
            log.warn("删除点赞通知缓存失败，key={}", key, e);
        }
    }
}
