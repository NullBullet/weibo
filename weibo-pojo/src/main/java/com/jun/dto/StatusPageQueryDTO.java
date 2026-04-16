package com.jun.dto;

import lombok.Data;

import java.io.Serializable;

@Data
public class StatusPageQueryDTO implements Serializable {

    //页码
    private Integer page;

    //每页记录数
    private Integer pageSize;

    //搜索关键
    private String q;

    private Long userId;
}
