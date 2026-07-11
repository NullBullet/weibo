package com.jun.mapper;

import com.jun.WeiboServerApplicationTests;
import com.jun.entity.Comment;
import com.jun.entity.Statuses;
import com.jun.entity.User;
import com.jun.entity.UserStatistics;
import com.jun.vo.SaveStatusVO;
import org.apache.ibatis.annotations.*;

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

    @Select("select a.target_type as targetType, a.target_id as targetId, " +
            "count(*) as likeCount, max(a.created_at) as lastLikeTime " +
            "from attitudes a " +
            "where (a.target_type = 1 and a.user_id != (select s.user_id from statuses s where s.id = a.target_id and s.is_deleted = 0)) " +
            "   or (a.target_type = 2 and a.user_id != (select c.user_id from comments c where c.id = a.target_id and c.is_deleted = 0)) " +
            "group by a.target_type, a.target_id")
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

    // ======================== 测试数据写入 ========================

    @Update("update users set userType = #{userType} where id = #{userId}")
    int updateUserType(@Param("userId") Long userId, @Param("userType") Integer userType);

    /**
     * 直接插入微博（仿照 StatusesService.saveStatus 逻辑，绕过 XML 绑定问题）
     * 使用 SaveStatusVO 接收自动生成的主键
     */
    @Insert("insert into statuses(user_id, content) values(#{userId}, #{saveStatusVO.content})")
    @Options(useGeneratedKeys = true, keyProperty = "saveStatusVO.statusId")
    int insertStatus(@Param("userId") Long userId, @Param("saveStatusVO") SaveStatusVO saveStatusVO);

    // ======================== 互动: 评论插入 ========================

    @Insert("insert into comments(status_id, user_id, parent_id, reply_user_id, content, created_at) " +
            "values(#{statusId}, #{userId}, #{parentId}, #{replyUserId}, #{content}, #{createdAt})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int saveComment(Comment comment);

    @Select("select id from statuses where id = #{statusId} and is_deleted = 0")
    Long getPostById(@Param("statusId") Long statusId);

    // ======================== 消费者逻辑 ========================

    /**
     * 获取目标作者的 user_id（仿 NotificationsMapper.getOwnerByTargetId）
     */
    @Select("select case when #{targetType} = 1 "
            + "then (select user_id from statuses where id = #{targetId}) "
            + "else (select user_id from comments where id = #{targetId}) end")
    Long getOwnerByTargetId(@Param("targetType") Integer targetType, @Param("targetId") Long targetId);

    /**
     * 点赞通知 upsert：like_count +1（仿 NoticeLikeConsumer）
     */
    @Insert("insert into notification_like(receiver_id, target_type, target_id, like_count, last_like_time, is_read, create_time) "
            + "values(#{receiverId}, #{targetType}, #{targetId}, 1, NOW(), 0, NOW()) "
            + "on duplicate key update like_count = like_count + 1, last_like_time = NOW(), is_read = 0")
    void upsertLikeNotificationSeed(@Param("receiverId") Long receiverId,
                                    @Param("targetType") Integer targetType,
                                    @Param("targetId") Long targetId);

    /**
     * 更新微博评论计数（仿 CommentsMapper.updateStatusCommentCount）
     */
    @Update("update statuses set comments_count = comments_count + 1 where id = #{statusId}")
    int updateStatusCommentCount(@Param("statusId") Long statusId);

    /**
     * 创建转发微博并返回自增 ID（仿 PostMapper.saveForwardStatus）
     */
    @Insert("insert into statuses(user_id, content, status_type, original_status_id, created_at) "
            + "values(#{userId}, #{content}, #{statusType}, #{originalStatusId}, #{createdAt})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int saveForwardStatus(Statuses forwardStatus);
}
