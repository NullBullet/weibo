package com.jun.entity;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 用户统计信息实体类
 */
@Data
public class UserStatistics implements Serializable {
    /**
     * 统计 ID
     */
    private Long id;

    /**
     * 用户 ID
     */
    private Long userId;

    /**
     * 关注数
     */
    private Integer followingCount;

    /**
     * 粉丝数
     */
    private Integer followersCount;

    /**
     * 微博数
     */
    private Integer statusesCount;

    /**
     * 获赞数
     */
    private Integer likesCount;

    /**
     * 更新时间
     */
    private LocalDateTime updatedAt;
}
