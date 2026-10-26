package com.jun.mapper;

import com.jun.entity.Statuses;
import org.apache.ibatis.annotations.*;

import java.util.List;

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

    @Update("update user_statistics set following_count = GREATEST(0, following_count + #{delta}) where user_id = #{userId}")
    int updateFollowingCount(@Param("userId") Long userId, @Param("delta") int delta);

    @Update("update user_statistics set followers_count = GREATEST(0, followers_count + #{delta}) where user_id = #{userId}")
    int updateFollowersCount(@Param("userId") Long userId, @Param("delta") int delta);

    @Select("select id from statuses where user_id = #{userId} and is_deleted = 0")
    List<Long> getPostIdsByUserId(@Param("userId") Long userId);

    @Select("select id, created_at from statuses where user_id = #{userId} and is_deleted = 0 order by created_at desc limit 200")
    List<Statuses> getRecentPostsByUserId(@Param("userId") Long userId);

    /** 我关注了谁：user_following 中 follower_id = 我的 followee_id 列表 */
    @Select("select followee_id from user_following where follower_id = #{userId}")
    List<Long> getFollowingIds(@Param("userId") Long userId);

    /** 谁关注了我：user_follower 中 followee_id = 我的 follower_id 列表 */
    @Select("select follower_id from user_follower where followee_id = #{userId}")
    List<Long> getFansIds(@Param("userId") Long userId);
}
