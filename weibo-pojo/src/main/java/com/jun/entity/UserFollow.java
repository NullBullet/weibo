package com.jun.entity;

import lombok.Data;
import java.time.LocalDateTime;

/**
 * 用户关注表 Entity
 */
@Data
public class UserFollow {
    private Long id;
    private Long userId; // 谁关注
    private Long targetId; // 关注谁
    private Integer status; // 0-取消 1-关注
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}