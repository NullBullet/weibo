package com.jun.mapper;

import com.jun.dto.ToggleLikeDTO;
import org.apache.ibatis.annotations.*;

@Mapper
public interface PostMapper {
    @Insert("insert into attitudes(user_id, target_type, target_id) values (#{userId},#{targetType},#{targetId})")
    void saveLikeInfo(ToggleLikeDTO toggleLikeDTO);

    @Update("update statuses set attitudes_count = #{likeCount} where id = #{statusId}")
    void updateStatusLikeCount(@Param("likeCount") Long likeCount,@Param("statusId") Long statusId);

    @Delete("delete from attitudes where user_id = #{userId} and target_type = #{targetType} and target_id = #{targetId}")
    void deleteLikeInfo(ToggleLikeDTO toggleLikeDTO);
}
