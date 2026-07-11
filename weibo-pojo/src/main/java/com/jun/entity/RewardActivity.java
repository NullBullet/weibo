package com.jun.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 抽奖活动实体类
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RewardActivity implements Serializable {

    /**
     * 活动 ID
     */
    private Long id;

    /**
     * 关联微博 ID
     */
    private Long statusId;

    /**
     * 创建者用户 ID
     */
    private Long creatorId;

    /**
     * 活动类型：1-限量参与，2-时间范围
     */
    private Integer activityType;

    /**
     * 奖品名称
     */
    private String rewardName;

    /**
     * 奖品描述
     */
    private String rewardDesc;

    /**
     * 是否仅粉丝参与
     */
    private Boolean fanOnly;

    /**
     * 最大参与人数（限量模式）
     */
    private Integer participantLimit;

    /**
     * 最终中奖人数
     */
    private Integer winnerCount;

    /**
     * 开始时间（时间范围模式）
     */
    private LocalDateTime startTime;

    /**
     * 结束时间
     */
    private LocalDateTime endTime;

    /**
     * 活动状态：0-未开始，1-进行中，2-已结束
     */
    private Integer status;

    /**
     * 当前参与人数
     */
    private Integer participantCount;

    /**
     * 是否已开奖：0-未开奖，1-已开奖
     */
    private Integer drawn;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;
}