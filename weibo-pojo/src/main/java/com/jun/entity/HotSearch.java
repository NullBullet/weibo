package com.jun.entity;

import lombok.Data;
import java.time.LocalDateTime;

/**
 * 热搜表 Entity
 */
@Data
public class HotSearch {
    private Long id;
    private String keyword;
    private Long searchCount;
    private Integer rank;
    private Integer trend; // 0-平 1-升 2-降
    private String icon; // 热/爆/新
    private Integer isRecommend;
    private Integer status; // 0-隐藏 1-显示
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private LocalDateTime createTime;
}