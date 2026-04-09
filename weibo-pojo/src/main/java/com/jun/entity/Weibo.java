package com.jun.entity;

import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 微博表 Entity
 */
@Data
public class Weibo {
    private Long id;
    private Long userId;
    private String content;
    private Integer type; // 1-普通 2-视频 3-图文
    private Integer status; // 0-删除 1-公开 2-私密 3-仅粉丝
    private Integer isTop; // 0-否 1-是
    private Integer likeCount;
    private Integer commentCount;
    private Integer repostCount;
    private Integer collectCount;
    private String location;
    private String device;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}