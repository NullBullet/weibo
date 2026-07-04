package com.jun.mq.message;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 删除微博 MQ 消息
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DeletePostMessage implements Serializable {

    /**
     * MQ 消息唯一 ID（幂等去重）
     */
    private String messageId;

    /**
     * 执行删除的用户 ID
     */
    private Long userId;

    /**
     * 被删除的微博 ID
     */
    private Long statusId;

    /**
     * 重试次数
     */
    private Integer retryCount;
}