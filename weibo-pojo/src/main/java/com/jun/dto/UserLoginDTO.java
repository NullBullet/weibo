package com.jun.dto;

import lombok.Data;

@Data
public class UserLoginDTO {
    private String username;
    private String password;

    /**
     * 登录 IP（controller 层注入）
     */
    private String ipAddress;

    /**
     * 用户代理（controller 层注入）
     */
    private String userAgent;

    /**
     * 验证码 ID（前端传入）
     */
    private String captchaId;

    /**
     * 验证码文本（前端传入）
     */
    private String captchaCode;
}