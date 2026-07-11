package com.jun.mq.message;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RewardWinMessage {

    /**
     * MQ消息唯一ID（幂等去重）
     */
    private String messageId;

    /**
     * 活动 ID
     */
    private Long activityId;

    /**
     * 奖品名称
     */
    private String rewardName;

    /**
     * 中奖用户 ID
     */
    private Long userId;

    /**
     * 重试次数
     */
    private Integer retryCount;
}