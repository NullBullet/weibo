package com.jun.utils;

import com.jun.constant.RedisConstants;
import com.jun.entity.Statuses;
import com.jun.mapper.FollowMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 用户收件箱缓存（feed:user:{userId} -> ZSET(postId, createTime)）的统一维护入口。
 * <p>
 * 与点赞/关注集合不同，收件箱没有独立源表：它是发帖时经 FeedConsumer 向粉丝扇出的结果。
 * 重建口径与扇出保持一致——只聚合「我关注的人」的未删除帖子（自己的帖子不进自己的收件箱），
 * 按 createTime 倒序取最近 {@link #FEED_MAX_SIZE} 条，等价于「每个关注者各回填 200 条再全局裁剪」。
 * <p>
 * - 写路径（FeedConsumer 扇出）：仅追加到已存在的收件箱，缺失的直接跳过，交由读路径从 DB
 * 重建。若在缺失时直接 ZADD，会生成只含本条帖子的半截收件箱，用户首页将长期只剩这一条；
 * - 读路径（首页/热门）：命中直接返回，缺失时按关注关系重建并写 7 天 TTL；
 * - 关注回填（FollowServiceImpl）：回填前先确保收件箱完整，避免 key 缺失时只回填被关注者
 * 的帖子而丢掉其他关注者的历史内容；
 * - 空收件箱不落 key：ZSET 无法用哨兵占位（成员会被当作 statusId 解析），无关注或关注者
 * 均无帖子时保持 key 不存在，读路径每次先比对关注集合再决定是否查库。
 */
@Slf4j
@Component
public class FeedHelper {

    /**
     * 收件箱最大保留条数，与扇出时的裁剪范围保持一致
     */
    public static final int FEED_MAX_SIZE = 200;

    @Resource
    private RedisTemplate redisTemplate;
    @Resource
    private FollowMapper followMapper;
    @Resource
    private FollowSetHelper followSetHelper;

    /**
     * 读路径：确保收件箱存在（key 缺失时从关注对象的最近帖子重建）。
     * 异常仅记录日志不传播，失败时退化为返回空列表。
     */
    public void ensureFeed(Long userId) {
        if (userId == null) {
            return;
        }
        String feedKey = RedisConstants.FEED_USER_KEY + userId;
        try {
            if (Boolean.TRUE.equals(redisTemplate.hasKey(feedKey))) {
                return;
            }

            // 关注集合可能自身也缺失，复用其读路径兜底后再取
            followSetHelper.ensureFollowing(userId);
            Set<Object> raw = redisTemplate.opsForSet()
                    .members(RedisConstants.FOLLOW_FOLLOWING_KEY + userId);
            if (raw == null || raw.isEmpty()) {
                return;
            }
            List<Long> followingIds = new ArrayList<>();
            for (Object o : raw) {
                String value = o.toString();
                // 跳过空集合哨兵
                if (FollowSetHelper.EMPTY_SENTINEL.equals(value)) {
                    continue;
                }
                followingIds.add(Long.valueOf(value));
            }
            if (followingIds.isEmpty()) {
                return;
            }

            List<Statuses> posts = followMapper.getRecentPostsByUserIds(followingIds, FEED_MAX_SIZE);
            if (posts == null || posts.isEmpty()) {
                log.info("重建用户收件箱结果为空: userId={}, following={}", userId, followingIds.size());
                return;
            }

            redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
                for (Statuses post : posts) {
                    connection.zSetCommands().zAdd(
                            feedKey.getBytes(),
                            (double) post.getCreatedAt().toEpochSecond(ZoneOffset.UTC),
                            post.getId().toString().getBytes()
                    );
                }
                connection.keyCommands().expire(feedKey.getBytes(), RedisConstants.FEED_USER_TTL);
                return null;
            });
            log.info("重建用户收件箱: userId={}, following={}, posts={}",
                    userId, followingIds.size(), posts.size());
        } catch (Exception e) {
            log.warn("重建用户收件箱时异常: userId={}", userId, e);
        }
    }

    /**
     * 写路径（FeedConsumer 扇出）：仅向已存在的收件箱追加并裁剪到 200 条，缺失的跳过。
     * key 缺失说明用户较长时间未访问首页，下次访问时由 {@link #ensureFeed(Long)} 从 DB 全量
     * 重建（重建含本条帖子，因帖子早已落库），因此跳过不会漏推。
     * 异常向上抛出，交由 FeedConsumer 的重试/死信机制处理。
     */
    public void appendIfPresent(Collection<String> fanIds, Long statusId, Long createTime) {
        if (fanIds == null || fanIds.isEmpty() || statusId == null || createTime == null) {
            return;
        }
        List<byte[]> feedKeys = new ArrayList<>();
        for (String fanId : fanIds) {
            if (FollowSetHelper.EMPTY_SENTINEL.equals(fanId)) {
                continue;
            }
            feedKeys.add((RedisConstants.FEED_USER_KEY + fanId).getBytes());
        }
        if (feedKeys.isEmpty()) {
            return;
        }

        // pipeline 批量探测，避免逐粉丝一次网络往返
        List<Object> exists = redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
            for (byte[] key : feedKeys) {
                connection.keyCommands().exists(key);
            }
            return null;
        });

        List<byte[]> presentKeys = new ArrayList<>();
        for (int i = 0; i < feedKeys.size(); i++) {
            if (Boolean.TRUE.equals(exists.get(i))) {
                presentKeys.add(feedKeys.get(i));
            }
        }
        if (presentKeys.isEmpty()) {
            log.info("收件箱均不存在，跳过本次扇出写入（待读路径重建）: statusId={}, fans={}",
                    statusId, feedKeys.size());
            return;
        }

        byte[] member = statusId.toString().getBytes();
        long score = createTime;
        redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
            for (byte[] key : presentKeys) {
                connection.zSetCommands().zAdd(key, score, member);
                // 只保留最近 FEED_MAX_SIZE 条
                connection.zSetCommands().zRemRange(key, 0, -(FEED_MAX_SIZE + 1));
                connection.keyCommands().expire(key, RedisConstants.FEED_USER_TTL);
            }
            return null;
        });
        log.info("收件箱写入完成: statusId={}, 写入={}, 跳过不存在={}",
                statusId, presentKeys.size(), feedKeys.size() - presentKeys.size());
    }

    /**
     * 读路径：访问命中后续期，活跃用户的收件箱不过期
     */
    public void refreshTtl(Long userId) {
        if (userId == null) {
            return;
        }
        try {
            redisTemplate.expire(RedisConstants.FEED_USER_KEY + userId,
                    RedisConstants.FEED_USER_TTL, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("续期用户收件箱时异常: userId={}", userId, e);
        }
    }
}
