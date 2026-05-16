package com.jun.mapper;

import com.jun.dto.RepostDTO;
import com.jun.dto.ToggleLikeDTO;
import com.jun.entity.Statuses;
import com.jun.vo.StatusVO;
import org.apache.ibatis.annotations.*;

import java.time.LocalDateTime;

@Mapper
public interface PostMapper {
    @Insert("insert into attitudes(user_id, target_type, target_id) values (#{userId},#{targetType},#{targetId})")
    void saveLikeInfo(ToggleLikeDTO toggleLikeDTO);

    @Update("update statuses set attitudes_count = attitudes_count + #{likeCount} where id = #{statusId}")
    void updateStatusLikeCount(@Param("likeCount") int likeCount,@Param("statusId") Long statusId);

    @Delete("delete from attitudes where user_id = #{userId} and target_type = #{targetType} and target_id = #{targetId}")
    void deleteLikeInfo(ToggleLikeDTO toggleLikeDTO);

    @Update("update user_statistics set likes_count = likes_count + #{likeCount} where user_id = #{userId}")
    void updateStatistics(int likeCount, Long userId);

    @Select("select original_status_id from statuses where id = #{forwardId}")
    Long getOriginalStatus(Long forwardId);

    void saveForwardStatus(Statuses forwardStatus);

    @Update("update statuses set reposts_count = reposts_count + #{repostCount} where id = #{originalStatusId}")
    void updateStatusRepostCount(int repostCount, Long originalStatusId);
}
