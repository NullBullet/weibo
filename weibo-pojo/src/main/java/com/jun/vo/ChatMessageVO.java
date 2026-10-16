package com.jun.vo;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ChatMessageVO {

    private String type;

    private Long messageId;

    private Long conversationId;

    private Long senderId;

    private Long receiverId;

    private String content;

    private LocalDateTime createTime;

    private String clientMessageId;
}