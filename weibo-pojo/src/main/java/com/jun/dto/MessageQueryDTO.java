package com.jun.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDate;

@Data
public class MessageQueryDTO implements Serializable {

    //页码
    private Integer page;

    //每页记录数
    private Integer pageSize;

    private Long conversationId;
}
