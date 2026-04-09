package com.jun.entity;

import lombok.Data;
import java.time.LocalDateTime;

/**
 * 微博图片表 Entity
 */
@Data
public class WeiboImage {
    private Long id;
    private Long weiboId;
    private String imageUrl;
    private Integer orderNum;
    private Integer width;
    private Integer height;
    private LocalDateTime createTime;
}