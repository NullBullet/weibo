package com.jun.entity;

import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 活动表 Entity (含秒杀)
 */
@Data
public class Activity {
    private Long id;
    private String title;
    private String description;
    private String imageUrl;
    private BigDecimal originalPrice;
    private BigDecimal activityPrice;
    private Integer totalStock;
    private Integer remainingStock;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private Integer status; // 0-下架 1-进行中 2-已结束
    private Integer limitPerUser;
    private Integer participantCount;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}