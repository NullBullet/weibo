package com.jun.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * notification_comment 表查询原始结果（中间 DTO，不直接返回前端）
 */
@Data
public class CommentNotificationRawDTO {
    private Long notificationId;
    private Long senderId;
    private Integer targetType;
    private Long targetId;
    private Long commentId;
    private Integer isRead;
    private LocalDateTime createTime;
}
