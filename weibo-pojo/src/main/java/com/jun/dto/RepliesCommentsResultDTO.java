package com.jun.dto;

import lombok.Builder;
import lombok.Data;

import java.io.Serializable;

@Builder
@Data
public class RepliesCommentsResultDTO implements Serializable {

    //页码
    private Integer page;

    //每页记录数
    private Integer pageSize;

    private Long commentId;
}
