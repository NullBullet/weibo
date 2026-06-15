package com.jun.mq.message;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CommentMessage {

    /**
     * MQ消息唯一ID（幂等去重）
     */
    private String messageId;

    /**
     * 评论者用户ID
     */
    private Long userId;

    /**
     * 微博ID
     */
    private Long statusId;

    /**
     * 父评论ID（一级评论为 null）
     */
    private Long parentId;

    /**
     * 回复的目标用户ID
     */
    private Long replyUserId;

    /**
     * 评论ID（CommentConsumer 落库后回填，用于通知）
     */
    private Long commentId;

    /**
     * 评论内容
     */
    private String content;

    /**
     * 重试次数
     */
    private Integer retryCount;
}
