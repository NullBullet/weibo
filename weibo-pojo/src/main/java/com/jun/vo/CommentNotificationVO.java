package com.jun.vo;

import lombok.Data;

/**
 * 评论通知 VO
 */
@Data
public class CommentNotificationVO {
    private Integer notificationId;
    private SenderVO sender;
    private String targetType;
    private Integer targetId;
    private Integer commentId;
    private String content;
    private String targetContent;
    private String createTime;
    private Integer isRead;
    private Boolean isLiked;
}
