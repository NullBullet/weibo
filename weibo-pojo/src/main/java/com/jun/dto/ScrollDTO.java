package com.jun.dto;

import lombok.Data;

import java.io.Serializable;

@Data
public class ScrollDTO implements Serializable {

    //热度分数
    private Double lastScore;

    private Integer offset;

    //每页记录数
    private Integer pageSize;

}
