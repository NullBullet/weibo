package com.jun.vo;

import lombok.Data;

import java.io.Serializable;

/**
 * 系统通知 VO
 */
@Data
public class SystemNotificationVO implements Serializable {

    private Long notificationId;

    private String category;

    private String title;

    private String content;

    private Long relatedId;

    private String relatedType;

    private Integer isRead;

    private String createTime;
}