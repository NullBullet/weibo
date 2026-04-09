package com.jun.entity;

import lombok.Data;
import java.time.LocalDateTime;

/**
 * 用户表 Entity
 */
@Data
public class User {
    private Long id;
    private String username;
    private String password;
    private String email;
    private String avatar;
    private String nickname;
    private Integer gender; // 0-未知 1-男 2-女
    private LocalDateTime birthday;
    private String location;
    private String signature;
    private Integer isVip; // 0-否 1-是
    private Integer vipLevel;
    private Integer followerCount;
    private Integer followingCount;
    private Integer weiboCount;
    private Integer status; // 0-禁用 1-正常 2-待审核
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    private LocalDateTime lastLoginTime;
}