package com.jun.mapper;

import com.jun.dto.CommentNotificationRawDTO;
import com.jun.vo.LikeUserVO;
import com.jun.vo.NotificationRawVO;
import org.apache.ibatis.annotations.MapKey;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;
import java.util.Set;

@Mapper
public interface NotificationsMapper {

    List<NotificationRawVO> getLikeNotifications(@Param("receiverId") Long receiverId);

    @MapKey("id")
    List<Map<String, Object>> getPostContents(@Param("postIds") List<Integer> postIds);

    @MapKey("id")
    List<Map<String, Object>> getCommentContents(@Param("commentIds") List<Integer> commentIds);

    @MapKey("id")
    List<Map<String, Object>> getBatchRecentLikers(@Param("targetType") Integer targetType,
                                                   @Param("targetIds") List<Integer> targetIds);

    List<LikeUserVO> getLikeUsers(@Param("targetType") Integer targetType,
                                  @Param("targetId") Integer targetId);

    Integer countLike(@Param("targetType") Integer targetType,
                      @Param("targetId") Integer targetId);

    Integer getTargetTypeByTargetId(@Param("targetId") Integer targetId);

    Long getOwnerByTargetId(@Param("targetType") Integer targetType,
                            @Param("targetId") Long targetId);

    void upsertLikeNotification(@Param("receiverId") Long receiverId,
                                @Param("targetType") Integer targetType,
                                @Param("targetId") Long targetId);

    void decrementLikeNotification(@Param("receiverId") Long receiverId,
                                   @Param("targetType") Integer targetType,
                                   @Param("targetId") Long targetId);

    void deleteZeroCount(@Param("receiverId") Long receiverId,
                         @Param("targetType") Integer targetType,
                         @Param("targetId") Long targetId);

    List<CommentNotificationRawDTO> getCommentNotifications(@Param("receiverId") Long receiverId);

    @MapKey("id")
    List<Map<String, Object>> getSenderInfos(@Param("senderIds") Set<Long> senderIds);

    void insertCommentNotification(@Param("receiverId") Long receiverId,
                                   @Param("senderId") Long senderId,
                                   @Param("targetType") Integer targetType,
                                   @Param("targetId") Long targetId,
                                   @Param("commentId") Long commentId);
}
