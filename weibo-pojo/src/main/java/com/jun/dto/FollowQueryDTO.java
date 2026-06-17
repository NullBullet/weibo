package com.jun.dto;

import lombok.Data;

/**
 * 关注/粉丝列表查询参数 DTO
 */
@Data
public class FollowQueryDTO {
    private Integer page;
    private Integer pageSize;
    private String keyword;
    private Long targetId;
}
