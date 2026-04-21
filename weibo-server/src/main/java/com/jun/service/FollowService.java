package com.jun.service;

import com.jun.result.Result;

public interface FollowService {

    Result follow(Long currentUserId, Long targetUserId);

    Result unfollow(Long currentUserId,Long targetUserId);

    Result<Boolean> check(Long targetUserId);
}
