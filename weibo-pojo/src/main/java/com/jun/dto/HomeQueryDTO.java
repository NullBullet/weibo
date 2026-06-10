package com.jun.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDate;

@Data
public class HomeQueryDTO implements Serializable {

    //页码
    private Integer page;

    //每页记录数
    private Integer pageSize;

    private String keyword;

    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate startDate;

    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate endDate;

    // ======================== 收件箱游标分页 ========================

    /**
     * 上一页最后一条的 score（收件箱 ZSET 分页）
     */
    private Double lastScore;

    /**
     * 与 lastScore 相同 score 的偏移量
     */
    private Integer offset;
}
