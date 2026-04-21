package com.jun.entity;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 关注关系实体类
 */
@Data
public class Follow implements Serializable {

    /**
     * 关注 ID
     */
    private Long id;

    /**
     * 被关注用户 ID
     */
    private Long followeeId;

    /**
     * 用户 ID（关注者）
     */
    private Long followerId;

    /**
     * 是否关注：0-正常、1-拉黑
     */
    private Integer status;

    /**
     * 关注时间
     */
    private LocalDateTime createdAt;
}
