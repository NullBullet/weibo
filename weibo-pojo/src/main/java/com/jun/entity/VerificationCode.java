package com.jun.entity;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 验证码实体类
 */
@Data
public class VerificationCode implements Serializable {

    /**
     * 验证码 ID
     */
    private Long id;

    /**
     * 目标（手机号或邮箱）
     */
    private String target;

    /**
     * 验证码
     */
    private String code;

    /**
     * 验证码类型：1-注册，2-登录，3-找回密码，4-绑定手机/邮箱
     */
    private Integer codeType;

    /**
     * 是否已使用：0-否，1-是
     */
    private Integer isUsed;

    /**
     * 过期时间
     */
    private LocalDateTime expiresAt;

    /**
     * 创建时间
     */
    private LocalDateTime createdAt;
}
