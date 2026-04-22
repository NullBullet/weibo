package com.jun.service.impl;

import com.jun.context.BaseContext;
import com.jun.exception.FollowFailException;
import com.jun.result.Result;
import com.jun.service.FollowService;
import com.jun.mapper.FollowMapper;
import jakarta.annotation.Resource;
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

        redisTemplate.opsForSet().add(FOLLOW_FOLLOWING_KEY + currentUserId.toString(), targetUserId.toString());
        redisTemplate.opsForSet().add(FOLLOW_FANS_KEY + targetUserId, currentUserId.toString());
        redisTemplate.opsForValue().increment(COUNT_FOLLOWING_KEY + currentUserId);
        redisTemplate.opsForValue().increment(COUNT_FANS_KEY + targetUserId);
        return Result.success("关注成功");
    }

    @Transactional
    @Override
    public Result unfollow(Long currentUserId, Long targetUserId) {
        if (followMapper.deleteFollower(currentUserId,targetUserId)<=0||
        followMapper.deleteFollowing(currentUserId,targetUserId)<=0) {
            throw new FollowFailException(UNFOLLOW_FAIL);
        }
        redisTemplate.opsForSet().remove(FOLLOW_FOLLOWING_KEY + currentUserId, targetUserId.toString());
        redisTemplate.opsForSet().remove(FOLLOW_FANS_KEY + targetUserId, currentUserId.toString());
        redisTemplate.opsForValue().decrement(COUNT_FOLLOWING_KEY + currentUserId);
        redisTemplate.opsForValue().decrement(COUNT_FANS_KEY + targetUserId);
        return Result.success("取关成功");
    }

    @Override
    public Result<Boolean> check(Long targetUserId) {
        Long userId = BaseContext.getCurrentId();
        Boolean isFollowed = redisTemplate.opsForSet().isMember(FOLLOW_FOLLOWING_KEY + userId, targetUserId.toString());
        return Result.success(isFollowed);
    }
}
