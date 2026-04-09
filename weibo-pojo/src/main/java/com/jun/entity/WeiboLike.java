package com.jun.entity;

import lombok.Data;
import java.time.LocalDateTime;

/**
 * 微博点赞表 Entity
 */
@Data
public class WeiboLike {
    private Long id;
    private Long weiboId;
    private Long userId;
    private Integer status; // 0-取消 1-点赞
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}