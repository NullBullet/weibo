package com.jun.vo;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 用户实体类
 */
@Data
public class UserInfoVO {
    /**
     * 用户 ID
     */
    private Long id;

    /**
     * 用户名（登录账号）
     */
    private String username;

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
     * 创建时间
     */
    private LocalDateTime createdAt;

}
