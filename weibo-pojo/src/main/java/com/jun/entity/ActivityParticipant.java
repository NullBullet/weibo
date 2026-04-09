package com.jun.entity;

import lombok.Data;
import java.time.LocalDateTime;

/**
 * 活动参与记录表 Entity
 */
@Data
public class ActivityParticipant {
    private Long id;
    private Long activityId;
    private Long userId;
    private Integer status; // 0-失败 1-成功 2-已取消
    private String orderNo;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}