package com.jun.vo;

import lombok.Data;

/**
 * 会话列表 VO
 */
@Data
public class ConversationVO {
    /**
     * 会话 ID
     */
    private Long conversationId;

    /**
     * 对方用户 ID
     */
    private Long otherUserId;

    /**
     * 对方昵称
     */
    private String nickname;

    /**
     * 对方头像
     */
    private String avatar;

    /**
     * 最后一条消息内容
     */
    private String lastMessage;

    /**
     * 最后一条消息发送时间
     */
    private String lastMessageTime;

    /**
     * 未读消息数
     */
    private Integer unreadCount;
}
