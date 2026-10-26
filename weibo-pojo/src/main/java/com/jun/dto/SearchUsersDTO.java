package com.jun.dto;

import lombok.Data;

import java.io.Serializable;

@Data
public class SearchUsersDTO implements Serializable {

    //页码
    private Integer page;

    //每页记录数
    private Integer pageSize;

    private String q;
}
