package com.jun.mq.message;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RepostMessage {

    /**
     * MQ消息唯一ID（幂等去重）
     */
    private String messageId;

    /**
     * 转发者用户ID
     */
    private Long userId;

    /**
     * 原微博ID
     */
    private Long originalStatusId;

    /**
     * 转发内容
     */
    private String content;

    /**
     * 重试次数
     */
    private Integer retryCount;
}
