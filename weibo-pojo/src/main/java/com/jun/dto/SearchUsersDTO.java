package com.jun.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.jun.enumeration.SearchType;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDate;

@Data
public class SearchUsersDTO implements Serializable {

    //页码
    private Integer page;

    //每页记录数
    private Integer pageSize;

    private String q;
}
