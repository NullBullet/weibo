package com.jun.entity;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 微博图片实体类
 */
@Data
public class StatusImage implements Serializable {

    /**
     * 图片 ID
     */
    private Long id;

    /**
     * 微博 ID
     */
    private Long statusId;

    /**
     * 图片 URL
     */
    private String imageUrl;

    /**
     * 图片排序
     */
    private Integer imageOrder;

    /**
     * 创建时间
     */
    private LocalDateTime createdAt;
}
