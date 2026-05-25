package com.jun.mapper;

import com.jun.entity.Comment;
import com.jun.vo.ReplyCountVO;
import com.jun.vo.UserVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface CommentsMapper {

    int saveComment(Comment comment);

    Long getPostById(@Param("statusId") Long statusId);

    Comment getCommentById(@Param("commentId") Long commentId);

    List<Comment> getTopComments(@Param("statusId") Long statusId,
                                 @Param("offset") int offset,
                                 @Param("limit") int limit);

    List<Comment> getRepliesByCommentIds(@Param("commentIds") List<Long> commentIds,
                                         @Param("limit") int limit);

    List<Comment> getRepliesByCommentId(@Param("commentId") Long commentId,
                                        @Param("offset") int offset,
                                        @Param("limit") int limit);

    List<ReplyCountVO> countRepliesByCommentIds(@Param("commentIds") List<Long> commentIds);

    Long countTotalByStatusId(@Param("statusId") Long statusId);

    Long countRepliesByCommentId(@Param("commentId") Long commentId);

    List<UserVO> getUsersInfoByUserIds(@Param("userIds") List<Long> userIds);

    int updateStatusCommentCount(@Param("statusId") Long statusId);

    int softDeleteComment(@Param("commentId") Long commentId);

    int softDeleteRepliesByParentId(@Param("parentId") Long parentId);

    int countNonDeletedReplies(@Param("parentId") Long parentId);

    int decreaseStatusCommentCount(@Param("statusId") Long statusId, @Param("count") int count);
}
