package com.jun.entity;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 消息实体类
 */
@Data
public class Message implements Serializable {

    /**
     * 消息 ID
     */
    private Long id;

    /**
     * 接收用户 ID
     */
    private Long userId;

    /**
     * 发送用户 ID（系统消息为 NULL）
     */
    private Long senderId;

    /**
     * 消息类型：1-评论，2-点赞，3-关注，4-系统，5-私信
     */
    private Integer messageType;

    /**
     * 目标类型：1-微博，2-评论
     */
    private Integer targetType;

    /**
     * 目标 ID
     */
    private Long targetId;

    /**
     * 消息内容
     */
    private String content;

    /**
     * 是否已读：0-未读，1-已读
     */
    private Integer isRead;

    /**
     * 阅读时间
     */
    private LocalDateTime readAt;

    /**
     * 创建时间
     */
    private LocalDateTime createdAt;
}
