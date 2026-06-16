package com.jun.entity;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 会话成员关系实体类
 */
@Data
public class ConversationUser implements Serializable {

    /**
     * 关系 ID
     */
    private Long id;

    /**
     * 会话 ID
     */
    private Long conversationId;

    /**
     * 用户 ID
     */
    private Long userId;

    /**
     * 未读消息数
     */
    private Integer unreadCount;

    /**
     * 最后已读消息 ID
     */
    private Long lastReadMessageId;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    private LocalDateTime updateTime;
}
