package com.jun.entity;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 点赞实体类
 */
@Data
public class Attitude implements Serializable {

    /**
     * 点赞 ID
     */
    private Long id;

    /**
     * 用户 ID
     */
    private Long userId;

    /**
     * 目标类型：1-微博，2-评论
     */
    private Integer targetType;

    /**
     * 目标 ID（微博 ID 或评论 ID）
     */
    private Long targetId;

    /**
     * 创建时间
     */
    private LocalDateTime createdAt;
}
