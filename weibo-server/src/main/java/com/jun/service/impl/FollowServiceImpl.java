package com.jun.service.impl;

import com.jun.context.BaseContext;
import com.jun.exception.FollowFailException;
import com.jun.result.Result;
import com.jun.service.FollowService;
import com.jun.mapper.FollowMapper;
import jakarta.annotation.Resource;

import java.util.List;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static com.jun.constant.MessageConstant.FOLLOW_FAIL;
import static com.jun.constant.MessageConstant.UNFOLLOW_FAIL;
import static com.jun.constant.RedisConstants.*;

@Service
public class FollowServiceImpl implements FollowService {

    @Resource
    private RedisTemplate redisTemplate;
    @Resource
    private FollowMapper followMapper;

    @Transactional
    @Override
    public Result follow(Long currentUserId, Long targetUserId) {
        if(followMapper.saveFollower(currentUserId,targetUserId)<=0||
                followMapper.saveFollowing(currentUserId, targetUserId) <= 0){
            throw new FollowFailException(FOLLOW_FAIL);
        }

        // DB: 更新统计计数
        followMapper.updateFollowingCount(currentUserId, 1);
        followMapper.updateFollowersCount(targetUserId, 1);

        // Redis: 更新关注/粉丝集合和计数
        redisTemplate.opsForSet().add(FOLLOW_FOLLOWING_KEY + currentUserId.toString(), targetUserId.toString());
        redisTemplate.opsForSet().add(FOLLOW_FANS_KEY + targetUserId, currentUserId.toString());
        redisTemplate.opsForHash().increment(USER_STATS_KEY + currentUserId, "followingCount", 1);
        redisTemplate.opsForHash().increment(USER_STATS_KEY + targetUserId, "followersCount", 1);
        return Result.success("关注成功");
    }

    @Transactional
    @Override
    public Result unfollow(Long currentUserId, Long targetUserId) {
        if (followMapper.deleteFollower(currentUserId,targetUserId)<=0||
        followMapper.deleteFollowing(currentUserId,targetUserId)<=0) {
            throw new FollowFailException(UNFOLLOW_FAIL);
        }

        // DB: 更新统计计数
        followMapper.updateFollowingCount(currentUserId, -1);
        followMapper.updateFollowersCount(targetUserId, -1);

        // Redis: 更新关注/粉丝集合和计数
        redisTemplate.opsForSet().remove(FOLLOW_FOLLOWING_KEY + currentUserId, targetUserId.toString());
        redisTemplate.opsForSet().remove(FOLLOW_FANS_KEY + targetUserId, currentUserId.toString());
        redisTemplate.opsForHash().increment(USER_STATS_KEY + currentUserId, "followingCount", -1);
        redisTemplate.opsForHash().increment(USER_STATS_KEY + targetUserId, "followersCount", -1);

        // 清理收件箱：删除被取关用户的帖子
        String feedKey = FEED_USER_KEY + currentUserId;
        List<Long> postIds = followMapper.getPostIdsByUserId(targetUserId);
        if (postIds != null && !postIds.isEmpty()) {
            redisTemplate.opsForZSet().remove(feedKey, postIds.stream().map(String::valueOf).toArray());
        }

        return Result.success("取关成功");
    }

    @Override
    public Result<Boolean> check(Long targetUserId) {
        Long userId = BaseContext.getCurrentId();
        Boolean isFollowed = redisTemplate.opsForSet().isMember(FOLLOW_FOLLOWING_KEY + userId, targetUserId.toString());
        return Result.success(isFollowed);
    }
}
