package com.jun.mapper;

import com.jun.dto.StatusesImageDTO;
import com.jun.entity.Statuses;
import com.jun.vo.SaveStatusVO;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface StatusesMapper {
    int saveStatus(@Param("userId") Long userId, @Param("saveStatusVO") SaveStatusVO saveStatusVO);

    int saveStatusImages(@Param("statusesImageDTO") StatusesImageDTO statusesImageDTO,
                         @Param("statusId") Long statusId);

    @Select("select id as statusId, created_at from statuses where id = #{statusId}")
    SaveStatusVO getStatusById(Long statusId);

    @Update("update statuses set is_deleted = 1 where id = #{statusId} and is_deleted = 0")
    int softDeleteStatus(@Param("statusId") Long statusId);

    @Select("select id, user_id from comments where status_id = #{statusId} and is_deleted = 0")
    List<java.util.Map<String, Object>> getCommentsByStatusId(@Param("statusId") Long statusId);

    @Select("select user_id from statuses where id = #{statusId}")
    Long getUserIdByStatusId(@Param("statusId") Long statusId);

    /**
     * 按 id 批量查询帖子统计列（用于 post:stats 缓存重建）
     */
    @MapKey("id")
    List<Statuses> selectStatsByIds(@Param("ids") List<Long> ids);
}
