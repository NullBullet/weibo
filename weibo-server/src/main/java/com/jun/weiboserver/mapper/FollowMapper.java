package com.jun.weiboserver.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface FollowMapper {
    @Insert("insert into user_following  (followee_id, follower_id) values (#{targetUserId},#{currentUserId});")
    int saveFollower(Long currentUserId, Long targetUserId);

    @Insert("insert into user_follower (followee_id, follower_id) values (#{targetUserId},#{currentUserId});")
    int saveFollowing(Long currentUserId, Long targetUserId);

    @Delete("delete from user_following where follower_id = #{currentUserId} and followee_id = #{targetUserId}")
    int deleteFollower(Long currentUserId, Long targetUserId);

    @Delete("delete from user_follower where follower_id = #{currentUserId} and followee_id = #{targetUserId}")
    int deleteFollowing(Long currentUserId, Long targetUserId);
}
