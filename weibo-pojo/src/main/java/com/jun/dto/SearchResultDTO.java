package com.jun.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.jun.enumeration.SearchType;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
public class SearchResultDTO implements Serializable {

    //页码
    private Integer page;

    //每页记录数
    private Integer pageSize;

    private String q;

    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate startDate;

    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate endDate;

    private SearchType searchType;
}
