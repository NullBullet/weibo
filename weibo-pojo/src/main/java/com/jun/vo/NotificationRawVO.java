package com.jun.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * notification_like 表查询原始结果（中间 VO，不直接返回前端）
 */
@Data
public class NotificationRawVO {
    private Integer notificationId;
    private Integer targetType;
    private Integer targetId;
    private Integer likeCount;
    private LocalDateTime lastLikeTime;
}
