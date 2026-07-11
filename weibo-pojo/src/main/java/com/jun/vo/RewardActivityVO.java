package com.jun.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * 抽奖活动 VO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RewardActivityVO implements Serializable {

    private Long activityId;

    private Long statusId;

    private Long creatorId;

    private String activityType;

    private String rewardName;

    private String rewardDesc;

    private Boolean fanOnly;

    private Integer participantLimit;

    private Integer winnerCount;

    private String startTime;

    private String endTime;

    /**
     * 活动状态：0-未开始，1-进行中，2-已结束
     */
    private Integer status;

    /**
     * 当前参与人数
     */
    private Integer participantCount;

    /**
     * 当前用户参与状态：NONE-未参与，JOINED-已获资格，FULL-名额已满，ENDED-已结束
     */
    private String myParticipation;

    /**
     * 是否已开奖
     */
    private Boolean drawn;

    /**
     * 中奖者列表（开奖后返回）
     */
    private List<WinnerVO> winners;
}