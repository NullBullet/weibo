package com.jun.mapper;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * attitudes 表聚合查询结果（测试初始化用）
 */
@Data
public class AttitudeAggregate {
    private Integer targetType;
    private Long targetId;
    private Integer likeCount;
    private LocalDateTime lastLikeTime;
}
