package com.jun.entity;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 话题/标签实体类
 */
@Data
public class Hashtag implements Serializable {

    /**
     * 话题 ID
     */
    private Long id;

    /**
     * 话题名称
     */
    private String name;

    /**
     * 话题描述
     */
    private String description;

    /**
     * 使用次数
     */
    private Long useCount;

    /**
     * 是否热门：0-否，1-是
     */
    private Integer isHot;

    /**
     * 创建时间
     */
    private LocalDateTime createdAt;

    /**
     * 更新时间
     */
    private LocalDateTime updatedAt;
}
