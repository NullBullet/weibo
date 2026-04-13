package com.jun.entity;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 评论实体类
 */
@Data
public class Comment implements Serializable {

    /**
     * 评论 ID
     */
    private Long id;

    /**
     * 微博 ID
     */
    private Long statusId;

    /**
     * 用户 ID
     */
    private Long userId;

    /**
     * 父评论 ID（回复评论时）
     */
    private Long parentId;

    /**
     * 评论内容
     */
    private String content;

    /**
     * 点赞数
     */
    private Integer likesCount;

    /**
     * 是否删除：0-否，1-是
     */
    private Integer isDeleted;

    /**
     * 创建时间
     */
    private LocalDateTime createdAt;

    /**
     * 更新时间
     */
    private LocalDateTime updatedAt;
}
