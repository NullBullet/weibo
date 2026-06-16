package com.jun.dto;

import lombok.Data;

import java.io.Serializable;

@Data
public class SendDTO implements Serializable {

    private Long conversationId;
    private String content;
}
