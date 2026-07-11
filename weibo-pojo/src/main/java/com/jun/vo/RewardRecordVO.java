package com.jun.vo;

import lombok.Data;

import java.io.Serializable;

/**
 * 抽奖记录 VO
 */
@Data
public class RewardRecordVO implements Serializable {

    private Long id;

    private Long activityId;

    private Long userId;

    /**
     * 参与状态：0-已参与，1-已中奖，2-已领取
     */
    private Integer status;

    /**
     * 奖品名称
     */
    private String rewardName;

    /**
     * 奖品描述
     */
    private String rewardDesc;

    /**
     * 活动状态：0-未开始，1-进行中，2-已结束
     */
    private Integer activityStatus;

    /**
     * 中奖时间
     */
    private String winTime;

    /**
     * 参与时间
     */
    private String createTime;
}