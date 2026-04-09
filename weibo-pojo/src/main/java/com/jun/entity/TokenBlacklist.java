package com.jun.entity;

import lombok.Data;
import java.time.LocalDateTime;

/**
 * Token 黑名单表 Entity
 */
@Data
public class TokenBlacklist {
    private Long id;
    private String token;
    private Long userId;
    private LocalDateTime expireTime;
    private String reason;
    private LocalDateTime createTime;
}