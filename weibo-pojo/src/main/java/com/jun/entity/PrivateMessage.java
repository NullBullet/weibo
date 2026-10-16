package com.jun.entity;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 私信消息实体类
 */
@Data
public class PrivateMessage implements Serializable {

    /**
     * 消息 ID
     */
    private Long id;

    /**
     * 会话 ID
     */
    private Long conversationId;

    /**
     * 发送者用户 ID
     */
    private Long senderId;

    /**
     * 接收者用户 ID
     */
    private Long receiverId;

    /**
     * 消息内容
     */
    private String content;

    /**
     * 是否已读：0-未读，1-已读
     */
    private Integer isRead;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 消息类型：1-文本，2-图片，3-文件
     */
    private Integer messageType;

    /**
     * 消息状态：1-正常，2-撤回，3-已删除
     */
    private Integer status;

    private String clientMessageId;
}
