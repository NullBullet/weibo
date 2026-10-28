package com.jun.service.impl;

import com.jun.context.BaseContext;
import com.jun.entity.Statuses;
import com.jun.exception.FollowFailException;
import com.jun.mapper.FollowMapper;
import com.jun.result.Result;
import com.jun.service.FollowService;
import com.jun.utils.FeedHelper;
import com.jun.utils.FollowSetHelper;
import com.jun.utils.UserStatsHelper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.ZoneOffset;
import java.util.List;

import static com.jun.constant.MessageConstant.FOLLOW_FAIL;
import static com.jun.constant.MessageConstant.UNFOLLOW_FAIL;
import static com.jun.constant.RedisConstants.*;

@Slf4j
@Service
public class FollowServiceImpl implements FollowService {

    @Resource
    private RedisTemplate redisTemplate;
    @Resource
    private FollowMapper followMapper;
    @Resource
    private UserStatsHelper userStatsHelper;
    @Resource
    private FollowSetHelper followSetHelper;
    @Resource
    private FeedHelper feedHelper;

    @Transactional
    @Override
    public Result follow(Long currentUserId, Long targetUserId) {
        // 防御：必须在事务内任何 user_statistics 写之前重建缺失的统计缓存，
        // 否则重建会读到未提交的计数导致双重加，也避免 HINCRBY 创建残缺 hash
        userStatsHelper.ensureStats(currentUserId);
        userStatsHelper.ensureStats(targetUserId);

        // 防御：关系集合缺失时先从 DB 重建，且必须在下方 DB 写入之前——
        // 否则 key 丢失后 SADD 会形成仅含本次关注者的半截快照，FeedConsumer 据此推送将
        // 漏掉该用户全部其他粉丝
        followSetHelper.ensureFollowing(currentUserId);
        followSetHelper.ensureFans(targetUserId);

        // 确保收件箱完整：此时关注集合仍是事务前状态，关注关系提交后再回填被关注者帖子。
        // 若在 key 缺失时直接回填，会只包含本次被关注者的帖子而丢掉其他关注者的历史内容。
        feedHelper.ensureFeed(currentUserId);

        if (followMapper.saveFollower(currentUserId, targetUserId) <= 0 ||
                followMapper.saveFollowing(currentUserId, targetUserId) <= 0) {
            throw new FollowFailException(FOLLOW_FAIL);
        }

        // DB: 更新统计计数
        followMapper.updateFollowingCount(currentUserId, 1);
        followMapper.updateFollowersCount(targetUserId, 1);

        // 事务内读取回填数据，避免在 afterCommit 中再次访问事务资源
        List<Statuses> recentPosts = followMapper.getRecentPostsByUserId(targetUserId);

        // 事务提交后再执行 Redis 增量维护，避免 DB 回滚但 Redis 已变更
        afterCommit(() -> updateFollowCache(currentUserId, targetUserId, recentPosts));

        return Result.success("关注成功");
    }

    @Transactional
    @Override
    public Result unfollow(Long currentUserId, Long targetUserId) {
        // 防御：必须在事务内任何 user_statistics 写之前重建缺失的统计缓存，
        // 否则重建会读到未提交的计数导致双重减，也避免 HINCRBY 创建残缺 hash
        userStatsHelper.ensureStats(currentUserId);
        userStatsHelper.ensureStats(targetUserId);

        // 防御：关系集合缺失时先从 DB 重建，且必须在下方 DB 删除之前——
        // 否则 SREM 在 key 缺失时空转，被取关的用户会一直留在重建后的粉丝集合里
        followSetHelper.ensureFollowing(currentUserId);
        followSetHelper.ensureFans(targetUserId);

        if (followMapper.deleteFollower(currentUserId, targetUserId) <= 0 ||
                followMapper.deleteFollowing(currentUserId, targetUserId) <= 0) {
            throw new FollowFailException(UNFOLLOW_FAIL);
        }

        // DB: 更新统计计数
        followMapper.updateFollowingCount(currentUserId, -1);
        followMapper.updateFollowersCount(targetUserId, -1);

        // 事务内读取待清理的帖子 ID，避免在 afterCommit 中再次访问事务资源
        List<Long> postIds = followMapper.getPostIdsByUserId(targetUserId);

        // 事务提交后再执行 Redis 增量维护，避免 DB 回滚但 Redis 已变更
        afterCommit(() -> updateUnfollowCache(currentUserId, targetUserId, postIds));

        return Result.success("取关成功");
    }

    /**
     * 关注提交后的 Redis 增量维护：集合 SADD、统计 HINCRBY、续期与 Feed 回填。
     * 异常仅记录日志，不改变已提交的数据库结果；缓存重建竞态见第 11 章后续改进。
     */
    private void updateFollowCache(Long currentUserId, Long targetUserId, List<Statuses> recentPosts) {
        try {
            redisTemplate.opsForSet().add(FOLLOW_FOLLOWING_KEY + currentUserId, targetUserId.toString());
            redisTemplate.opsForSet().add(FOLLOW_FANS_KEY + targetUserId, currentUserId.toString());
            redisTemplate.opsForHash().increment(USER_STATS_KEY + currentUserId, "followingCount", 1);
            redisTemplate.opsForHash().increment(USER_STATS_KEY + targetUserId, "followersCount", 1);
            // 统计值修改成功，续期缓存 TTL
            userStatsHelper.refreshTtl(currentUserId);
            userStatsHelper.refreshTtl(targetUserId);

            // 关系写入成功，续期关注/粉丝集合 TTL
            followSetHelper.refreshTtlFollowing(currentUserId);
            followSetHelper.refreshTtlFans(targetUserId);

            // 回填历史微博到收件箱
            String feedKey = FEED_USER_KEY + currentUserId;
            if (recentPosts != null && !recentPosts.isEmpty()) {
                redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
                    for (Statuses post : recentPosts) {
                        double score = post.getCreatedAt().toEpochSecond(ZoneOffset.UTC);
                        connection.zSetCommands().zAdd(
                                feedKey.getBytes(),
                                score,
                                post.getId().toString().getBytes()
                        );
                    }
                    return null;
                });
                // 回填写入成功，续期收件箱 TTL
                feedHelper.refreshTtl(currentUserId);
            }
        } catch (Exception e) {
            log.error("关注提交后更新 Redis 缓存失败: currentUserId={}, targetUserId={}",
                    currentUserId, targetUserId, e);
        }
    }

    /**
     * 取关提交后的 Redis 增量维护：集合 SREM、统计 HINCRBY、续期与收件箱清理。
     * 异常仅记录日志，不改变已提交的数据库结果；缓存重建竞态见第 11 章后续改进。
     */
    private void updateUnfollowCache(Long currentUserId, Long targetUserId, List<Long> postIds) {
        try {
            redisTemplate.opsForSet().remove(FOLLOW_FOLLOWING_KEY + currentUserId, targetUserId.toString());
            redisTemplate.opsForSet().remove(FOLLOW_FANS_KEY + targetUserId, currentUserId.toString());
            redisTemplate.opsForHash().increment(USER_STATS_KEY + currentUserId, "followingCount", -1);
            redisTemplate.opsForHash().increment(USER_STATS_KEY + targetUserId, "followersCount", -1);
            // 统计值修改成功，续期缓存 TTL
            userStatsHelper.refreshTtl(currentUserId);
            userStatsHelper.refreshTtl(targetUserId);

            // 关系移除成功，续期关注/粉丝集合 TTL
            followSetHelper.refreshTtlFollowing(currentUserId);
            followSetHelper.refreshTtlFans(targetUserId);

            // 清理收件箱：删除被取关用户的帖子
            String feedKey = FEED_USER_KEY + currentUserId;
            if (postIds != null && !postIds.isEmpty()) {
                redisTemplate.opsForZSet().remove(feedKey, postIds.stream().map(String::valueOf).toArray());
            }
        } catch (Exception e) {
            log.error("取关提交后更新 Redis 缓存失败: currentUserId={}, targetUserId={}",
                    currentUserId, targetUserId, e);
        }
    }

    /**
     * 数据库事务提交后执行缓存维护。若当前没有事务同步（例如非代理调用），
     * 则直接执行，保持与原逻辑一致的降级行为。
     */
    private void afterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }

    @Override
    public Result<Boolean> check(Long targetUserId) {
        Long userId = BaseContext.getCurrentId();
        // 读路径兜底：关注集合过期后从 DB 重建，避免已关注状态误报 false
        followSetHelper.ensureFollowing(userId);
        Boolean isFollowed = redisTemplate.opsForSet().isMember(FOLLOW_FOLLOWING_KEY + userId, targetUserId.toString());
        return Result.success(isFollowed);
    }
}
