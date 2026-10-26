package com.jun.dto;

import lombok.Data;

import java.io.Serializable;

@Data
public class MessageQueryDTO implements Serializable {

    //页码
    private Integer page;

    //每页记录数
    private Integer pageSize;

    private Long conversationId;
}
