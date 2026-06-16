package com.jun.vo;

import lombok.Data;

/**
 * 私信消息 VO
 */
@Data
public class MessageVO {
    /**
     * 消息 ID
     */
    private Long messageId;

    /**
     * 会话 ID
     */
    private Long conversationId;

    /**
     * 发送者用户 ID
     */
    private Long senderId;

    /**
     * 消息内容
     */
    private String content;

    /**
     * 发送时间
     */
    private String createTime;

    /**
     * 消息类型：1-文本，2-图片，3-文件
     */
    private Integer messageType;

    /**
     * 消息状态：1-正常，2-撤回，3-已删除
     */
    private Integer status;

    /**
     * 是否已读：0-未读，1-已读
     */
    private Integer isRead;
}
