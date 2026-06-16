package com.jun.entity;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 私信会话实体类
 */
@Data
public class Conversation implements Serializable {

    /**
     * 会话 ID
     */
    private Long id;

    /**
     * 发起会话用户 ID（用户对中较小 ID）
     */
    private Long user1Id;

    /**
     * 会话对方用户 ID（用户对中较大 ID）
     */
    private Long user2Id;

    /**
     * 最后一条消息内容（摘要）
     */
    private String lastMessage;

    /**
     * 最后一条消息发送时间
     */
    private LocalDateTime lastMessageTime;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    private LocalDateTime updateTime;
}
