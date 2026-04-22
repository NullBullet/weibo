package com.jun.mapper;

import com.jun.dto.StatusesImageDTO;
import com.jun.entity.Statuses;
import com.jun.vo.SaveStatusVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface StatusesMapper {
    int saveStatus(@Param("userId") Long userId, @Param("saveStatusVO") SaveStatusVO saveStatusVO);

    int saveStatusImages(@Param("statusesImageDTO") StatusesImageDTO statusesImageDTO,
                         @Param("statusId") Long statusId);

    @Select("select created_at from statuses where id = #{statusId}")
    SaveStatusVO getStatusById(Long statusId);
}
