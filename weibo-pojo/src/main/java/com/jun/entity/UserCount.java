package com.jun.entity;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class UserCount {
    private Long userId;
    private Integer followerCount;
    private Integer followingCount;
    private Integer weiboCount;
    private Integer likeCount;
    private LocalDateTime updateTime;
}