package com.jun.mapper;

import com.jun.entity.RewardActivity;
import com.jun.vo.ParticipantVO;
import com.jun.vo.RewardRecordVO;
import com.jun.vo.SystemNotificationVO;
import com.jun.vo.WinnerVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface RewardActivityMapper {

    void insert(RewardActivity activity);

    RewardActivity getById(@Param("id") Long id);

    RewardActivity getByStatusId(@Param("statusId") Long statusId);

    void updateStatus(@Param("id") Long id, @Param("status") Integer status);

    void updateParticipantCount(@Param("id") Long id, @Param("count") Integer count);

    @Update("update reward_record set status = 1, win_time = now() where activity_id = #{activityId} and user_id = #{userId}")
    void markWinner(@Param("activityId") Long activityId, @Param("userId") Long userId);

    void markActivityDrawn(@Param("id") Long id);

    List<Long> getParticipantUserIds(@Param("activityId") Long activityId);

    Integer countParticipants(@Param("activityId") Long activityId);

    List<ParticipantVO> getParticipantPage(@Param("activityId") Long activityId,
                                           @Param("offset") Integer offset,
                                           @Param("pageSize") Integer pageSize);

    List<WinnerVO> getWinners(@Param("activityId") Long activityId);

    List<RewardRecordVO> getRecordsByUserId(@Param("userId") Long userId,
                                            @Param("offset") Integer offset,
                                            @Param("pageSize") Integer pageSize);

    Integer countRecordsByUserId(@Param("userId") Long userId);

    List<RewardRecordVO> getWonRecordsByUserId(@Param("userId") Long userId,
                                               @Param("offset") Integer offset,
                                               @Param("pageSize") Integer pageSize);

    Integer countWonRecordsByUserId(@Param("userId") Long userId);

    Integer getActivityTypeById(@Param("id") Long id);

    List<RewardActivity> getActivitiesToDraw();

    Integer getParticipantLimitById(@Param("id") Long id);

    void insertWinNotification(@Param("userId") Long userId,
                               @Param("activityId") Long activityId,
                               @Param("content") String content);

    List<RewardActivity> getByStatusIds(@Param("statusIds") List<Long> statusIds);

    List<SystemNotificationVO> getSystemNotifications(@Param("userId") Long userId,
                                                      @Param("offset") Integer offset,
                                                      @Param("pageSize") Integer pageSize);

    Integer countSystemNotifications(@Param("userId") Long userId);

    @Update("update notification_system set is_read = 1 where user_id = #{userId} and is_read = 0")
    int markAllNotificationsRead(@Param("userId") Long userId);
}