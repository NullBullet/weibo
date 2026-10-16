package com.jun.dto;

import lombok.Data;

@Data
public class ChatMessageDTO {
    private Long conversationId;
    private String content;
    private String type;
    private String clientMessageId;
}