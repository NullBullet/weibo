package com.jun.entity;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 登录日志实体类
 */
@Data
public class LoginLog implements Serializable {

    /**
     * 日志 ID
     */
    private Long id;

    /**
     * 用户 ID
     */
    private Long userId;

    /**
     * 登录方式：1-密码，2-验证码，3-第三方
     */
    private Integer loginType;

    /**
     * 登录 IP
     */
    private String ipAddress;

    /**
     * 用户代理
     */
    private String userAgent;

    /**
     * 登录状态：0-失败，1-成功
     */
    private Integer loginStatus;

    /**
     * 失败原因
     */
    private String failReason;

    /**
     * 登录时间
     */
    private LocalDateTime createdAt;
}
