package com.jun.mq.message;

import com.fasterxml.jackson.annotation.JsonAlias;
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
     * 点赞目标ID：帖子ID 或 评论ID
     */
    @JsonAlias({"statusId", "commentId"})
    private Long targetId;

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