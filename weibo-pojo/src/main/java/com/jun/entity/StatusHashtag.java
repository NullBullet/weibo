package com.jun.entity;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 微博话题关联实体类
 */
@Data
public class StatusHashtag implements Serializable {

    /**
     * 关联 ID
     */
    private Long id;

    /**
     * 微博 ID
     */
    private Long statusId;

    /**
     * 话题 ID
     */
    private Long hashtagId;

    /**
     * 创建时间
     */
    private LocalDateTime createdAt;
}
