package com.jun.entity;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 用户实体类
 */
@Data
public class User implements Serializable {
    /**
     * 用户 ID
     */
    private Long id;

    /**
     * 用户名（登录账号）
     */
    private String username;

    /**
     * 密码（加密存储）
     */
    private String password;

    /**
     * 昵称
     */
    private String nickname;

    /**
     * 头像 URL
     */
    private String avatar;

    /**
     * 性别：0-保密，1-男，2-女
     */
    private Integer gender;

    /**
     * 生日
     */
    private LocalDateTime birthday;

    /**
     * 个人简介（最多 140 字）
     */
    private String bio;

    /**
     * 感情状况：0-不选，1-单身，2-恋爱中，3-已婚
     */
    private Integer relationshipStatus;

    /**
     * 家乡
     */
    private String hometown;

    /**
     * 手机号
     */
    private String phone;

    /**
     * 邮箱
     */
    private String email;

    /**
     * 账号状态：0-禁用，1-正常，2-待验证
     */
    private Integer status;

    /**
     * 最后登录时间
     */
    private LocalDateTime lastLoginTime;

    /**
     * 最后登录 IP
     */
    private String lastLoginIp;

    /**
     * 创建时间
     */
    private LocalDateTime createdAt;

    /**
     * 更新时间
     */
    private LocalDateTime updatedAt;

    /**
     * 删除时间（软删除）
     */
    private LocalDateTime deletedAt;

    /**
     * 用户类型：0-普通用户，1-大V用户
     */
    private Integer userType;
}
