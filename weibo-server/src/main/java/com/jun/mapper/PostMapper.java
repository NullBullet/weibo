package com.jun.mapper;

import com.jun.dto.RepostDTO;
import com.jun.dto.ToggleLikeDTO;
import com.jun.entity.Statuses;
import com.jun.vo.LikeRecordVO;
import com.jun.vo.StatusVO;
import org.apache.ibatis.annotations.*;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface PostMapper {
    @Insert("insert into attitudes(user_id, target_type, target_id, created_at) values (#{userId},#{targetTypeValue},#{targetId}, NOW())")
    void saveLikeInfo(ToggleLikeDTO toggleLikeDTO);

    @Update("update statuses set attitudes_count = attitudes_count + #{likeCount} where id = #{statusId}")
    void updateStatusLikeCount(@Param("likeCount") int likeCount, @Param("statusId") Long statusId);

    @Delete("delete from attitudes where user_id = #{userId} and target_type = #{targetTypeValue} and target_id = #{targetId}")
    void deleteLikeInfo(ToggleLikeDTO toggleLikeDTO);

    List<LikeRecordVO> getLikeRecords(@Param("targetType") int targetType, @Param("targetIds") List<Long> targetIds);

    @Update("update user_statistics set likes_count = likes_count + #{likeCount} where user_id = #{userId}")
    void updateStatistics(@Param("likeCount") int likeCount, @Param("userId") Long userId);

    @Update("update comments set likes_count = likes_count + #{likeCount} where id = #{commentId}")
    void updateCommentLikeCount(@Param("likeCount") int likeCount, @Param("commentId") Long commentId);

    @Select("select original_status_id from statuses where id = #{forwardId} and is_deleted = 0")
    Long getOriginalStatus(Long forwardId);

    @Select("select id from statuses where id = #{id} and is_deleted = 0")
    Long checkStatusExists(@Param("id") Long id);

    void saveForwardStatus(Statuses forwardStatus);

    @Update("update statuses set reposts_count = reposts_count + #{repostCount} where id = #{originalStatusId}")
    void updateStatusRepostCount(int repostCount, Long originalStatusId);

    @Select("select user_id from statuses where id = #{statusId} and is_deleted = 0")
    Long getUserIdByStatusId(@Param("statusId") Long statusId);
}
