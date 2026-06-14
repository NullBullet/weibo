package com.jun.vo;

import lombok.Data;

import java.util.List;

/**
 * 点赞详情 VO
 */
@Data
public class LikeDetailVO {
    private Integer targetId;
    private String content;
    private Integer total;
    private List<LikeUserVO> users;
}
