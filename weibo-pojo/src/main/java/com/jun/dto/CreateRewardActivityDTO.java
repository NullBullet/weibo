package com.jun.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * 创建抽奖活动 DTO
 */
@Data
public class CreateRewardActivityDTO implements Serializable {

    /**
     * 关联微博 ID
     */
    private Long statusId;

    /**
     * 活动类型：LIMIT_FORWARD - 限量参与，TIME_RANGE - 时间范围
     */
    private String activityType;

    /**
     * 奖品名称
     */
    private String rewardName;

    /**
     * 奖品描述
     */
    private String rewardDesc;

    /**
     * 是否仅粉丝参与，默认 true
     */
    private Boolean fanOnly;

    /**
     * 最大参与人数（限量模式必填）
     */
    private Integer limitCount;

    /**
     * 中奖人数
     */
    private Integer winnerCount;

    /**
     * 开始时间（时间范围模式必填）
     */
    private String startTime;

    /**
     * 结束时间（时间范围模式必填）
     */
    private String endTime;
}