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
     * 用户 ID（关注者）
     */
    private Long userId;

    /**
     * 被关注用户 ID
     */
    private Long followUserId;

    /**
     * 是否关注：0-取消，1-关注
     */
    private Integer isFollowed;

    /**
     * 创建时间
     */
    private LocalDateTime createdAt;

    /**
     * 更新时间
     */
    private LocalDateTime updatedAt;
}
