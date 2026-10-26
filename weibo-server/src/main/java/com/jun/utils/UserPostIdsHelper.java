package com.jun.utils;

import com.jun.constant.RedisConstants;
import com.jun.entity.Statuses;
import com.jun.mapper.UsersMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 用户微博时间线缓存（user:post:ids:{userId} ZSET）的统一维护入口。
 * <p>
 * 缓存只允许两种合法状态：完整快照，或不存在；禁止"仅含少量成员的半截快照"，
 * 否则读路径会因 ZSET 非空而跳过重建，导致用户主页缺少历史微博。
 * <p>
 * - 写路径（发帖/转发）：key 存在时增量 zAdd 并续期；key 不存在时不写增量，
 * 直接从数据库全量重建（本次新帖已落库，重建结果天然包含）；
 * - 读路径：命中续期，缺失时全量重建兜底。
 */
@Slf4j
@Component
public class UserPostIdsHelper {

    @Resource
    private RedisTemplate redisTemplate;
    @Resource
    private UsersMapper usersMapper;

    /**
     * 发帖/转发时写入时间线：key 存在（完整快照）则增量 zAdd + 续期；
     * key 不存在（过期或丢失）则全量重建，避免产生半截快照。
     */
    public void addPost(Long userId, Long statusId, long createTime) {
        String zsetKey = RedisConstants.USER_POST_IDS_KEY + userId;
        try {
            if (Boolean.TRUE.equals(redisTemplate.hasKey(zsetKey))) {
                redisTemplate.opsForZSet().add(zsetKey, statusId.toString(), createTime);
                refreshTtl(zsetKey);
            } else {
                // 全量重建（查库条件 is_deleted=0，已包含刚落库的本条微博）
                rebuild(userId);
            }
        } catch (Exception e) {
            // 写缓存失败不影响发帖主流程，读路径缺失时会重建兜底
            log.warn("维护用户微博时间线缓存失败: userId={}, statusId={}", userId, statusId, e);
        }
    }

    /**
     * 时间线 ZSET 缺失（TTL 过期或丢失）时从数据库全量重建。
     * 仅查询 id 与 created_at，轻量查询；zAdd 幂等，并发重建/写入最终一致。
     * 极小窗口内可能与删除消费并发导致短暂复活已删除的微博 ID，
     * 后续详情查询因 is_deleted=0 查不到按 miss 兜底，无需加锁。
     */
    public void rebuild(Long userId) {
        String zsetKey = RedisConstants.USER_POST_IDS_KEY + userId;
        List<Statuses> posts = usersMapper.getUserPostIdScores(userId);
        if (posts == null || posts.isEmpty()) {
            return;
        }
        redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
            for (Statuses post : posts) {
                connection.zAdd(
                        zsetKey.getBytes(),
                        post.getCreatedAt().toEpochSecond(ZoneOffset.UTC),
                        post.getId().toString().getBytes()
                );
            }
            return null;
        });
        refreshTtl(zsetKey);
        log.info("重建用户微博时间线缓存: userId={}, count={}", userId, posts.size());
    }

    /**
     * 刷新时间线缓存 TTL（写路径初始化 / 读路径命中续期）
     */
    public void refreshTtl(String zsetKey) {
        redisTemplate.expire(zsetKey, RedisConstants.USER_POST_IDS_TTL, TimeUnit.SECONDS);
    }
}
