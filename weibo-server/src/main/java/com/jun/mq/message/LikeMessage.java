package com.jun.mq.message;

import com.jun.enumeration.TargetType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LikeMessage {

    /**
     * 用户ID
     */
    private Long userId;

    /**
     * 帖子ID
     */
    private Long statusId;

    /**
     * 是否点赞
     */
    private Boolean isLiked;

    /**
     * 点赞目标类型
     */
    private TargetType targetType;

    /**
     * 重试次数
     */
    private Integer retryCount;

    /**
     * MQ消息唯一ID
     */
    private String messageId;
}