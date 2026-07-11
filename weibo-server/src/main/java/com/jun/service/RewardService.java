package com.jun.service;

import com.jun.dto.CreateRewardActivityDTO;
import com.jun.result.PageResult;
import com.jun.result.Result;
import com.jun.vo.ExtensionVO;

import java.util.List;
import java.util.Map;

public interface RewardService {

    /**
     * 创建抽奖活动
     */
    Result createActivity(CreateRewardActivityDTO dto);

    /**
     * 按微博 ID 查询活动（含当前用户参与状态）
     */
    Result getByStatusId(Long statusId);

    /**
     * 按活动 ID 查询详情（含当前用户参与状态）
     */
    Result getById(Long activityId);

    /**
     * 获取参与名单（分页）
     */
    Result getParticipants(Long activityId, Integer page, Integer pageSize);

    /**
     * 获取中奖名单
     */
    Result getWinners(Long activityId);

    /**
     * 获取我的抽奖记录（分页）
     */
    Result getMyRecords(Integer page, Integer pageSize, Integer onlyWon);

    /**
     * 处理转发参与活动
     */
    void handleForwardReward(Long activityId, Long userId, Long statusId);

    /**
     * 批量查询帖子扩展信息（statusId → 扩展列表），避免 N+1
     */
    Map<Long, List<ExtensionVO>> getExtensionsByStatusIds(List<Long> statusIds);
}