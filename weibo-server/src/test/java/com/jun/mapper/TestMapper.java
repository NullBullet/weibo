package com.jun.mapper;

import com.jun.WeiboServerApplicationTests;
import com.jun.entity.Comment;
import com.jun.entity.Statuses;
import com.jun.entity.User;
import com.jun.entity.UserStatistics;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

@Mapper
public interface TestMapper {
    @Select("select *from statuses where user_id=#{userId} order by created_at desc")
    List<Statuses> getStatusByUserId(Long userId);

    @Select("select * from users")
    Set<Long> getUserId();

    @Select("select *from statuses")
    List<Statuses> selectList(Object o);

    @Select("select *from users")
    List<User> selectListusers(Object o);

    boolean insertBatchSomeColumn(List<UserStatistics> statsListToSave);

    @org.apache.ibatis.annotations.Update("update user_statistics set following_count = #{followingCount}, followers_count = #{followersCount} where user_id = #{userId}")
    int updateUserFollowCounts(@org.apache.ibatis.annotations.Param("userId") Long userId,
                                @org.apache.ibatis.annotations.Param("followingCount") int followingCount,
                                @org.apache.ibatis.annotations.Param("followersCount") int followersCount);

    // ======================== 通知表初始化 ========================

    @Select("select target_type as targetType, target_id as targetId, " +
            "count(*) as likeCount, max(created_at) as lastLikeTime " +
            "from attitudes group by target_type, target_id")
    List<AttitudeAggregate> getAttitudeAggregates();

    @Select("select user_id from statuses where id = #{statusId} and is_deleted = 0")
    Long getPostOwnerId(@Param("statusId") Long statusId);

    @Select("select user_id from comments where id = #{commentId} and is_deleted = 0")
    Long getCommentOwnerId(@Param("commentId") Long commentId);

    @Insert("insert into notification_like(receiver_id, target_type, target_id, like_count, last_like_time) " +
            "values(#{receiverId}, #{targetType}, #{targetId}, #{likeCount}, #{lastLikeTime}) " +
            "on duplicate key update " +
            "like_count = values(like_count), " +
            "last_like_time = values(last_like_time)")
    void upsertNotificationLike(@Param("receiverId") Long receiverId,
                                @Param("targetType") Integer targetType,
                                @Param("targetId") Long targetId,
                                @Param("likeCount") Integer likeCount,
                                @Param("lastLikeTime") LocalDateTime lastLikeTime);

    // ======================== 评论通知初始化 ========================

    @Select("select * from comments where is_deleted = 0 order by created_at asc")
    List<Comment> getAllComments();

    @Insert("insert into notification_comment(receiver_id, sender_id, target_type, target_id, comment_id, is_read, create_time) " +
            "values(#{receiverId}, #{senderId}, #{targetType}, #{targetId}, #{commentId}, 0, #{createTime})")
    void insertCommentNotification(@Param("receiverId") Long receiverId,
                                   @Param("senderId") Long senderId,
                                   @Param("targetType") Integer targetType,
                                   @Param("targetId") Long targetId,
                                   @Param("commentId") Long commentId,
                                   @Param("createTime") LocalDateTime createTime);
}
