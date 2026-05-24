package com.jun.dto;

import lombok.Data;

import java.io.Serializable;

@Data
public class PostCommentsDTO implements Serializable {
    /**
     * 评论的微博 ID
     */
    private Long statusId;

    /**
     * 评论内容
     */
    private String content;

    /**
     * 父评论 ID（回复评论时传入，一级评论不传）
     */
    private Long parentId;

    /**
     * 回复的目标用户 ID（回复评论时传入，用于显示"@某用户"）
     */
    private Long replyUserId;
}
