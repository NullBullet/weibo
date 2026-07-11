package com.jun.mapper;

import com.jun.entity.RewardRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface RewardRecordMapper {

    void insert(RewardRecord record);

    @Select("select count(1) from reward_record where activity_id = #{activityId} and user_id = #{userId}")
    Integer countByActivityIdAndUserId(@Param("activityId") Long activityId, @Param("userId") Long userId);

    @Select("select status from reward_record where activity_id = #{activityId} and user_id = #{userId}")
    Integer getStatusByActivityIdAndUserId(@Param("activityId") Long activityId, @Param("userId") Long userId);
}