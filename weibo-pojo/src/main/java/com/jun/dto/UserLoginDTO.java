package com.jun.dto;

import lombok.Data;

@Data
public class UserLoginDTO {
    private String username;
    private String password;

    /** 登录 IP（controller 层注入） */
    private String ipAddress;

    /** 用户代理（controller 层注入） */
    private String userAgent;
}