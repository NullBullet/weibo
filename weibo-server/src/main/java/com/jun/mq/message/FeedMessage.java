package com.jun.mq.message;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FeedMessage {

    /**
     * MQ消息唯一ID（幂等去重）
     */
    private String messageId;

    /**
     * 发帖人用户ID（用于查粉丝列表）
     */
    private Long userId;

    /**
     * 帖子ID
     */
    private Long statusId;

    /**
     * 创建时间（epoch秒，作为收件箱ZSET的score）
     */
    private Long createTime;

    /**
     * 重试次数
     */
    private Integer retryCount;
}
