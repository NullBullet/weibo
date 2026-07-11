package com.jun.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 抽奖参与记录实体类
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RewardRecord implements Serializable {

    /**
     * 记录 ID
     */
    private Long id;

    /**
     * 活动 ID
     */
    private Long activityId;

    /**
     * 用户 ID
     */
    private Long userId;

    /**
     * 参与状态：0-已参与，1-已中奖，2-已领取
     */
    private Integer status;

    /**
     * 中奖时间
     */
    private LocalDateTime winTime;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;
}