package com.jun.dto;

import lombok.Data;

@Data
public class UserLoginDTO {
    private String username;
    private String password;
    private String captcha;     // 验证码
    private String uuid;        // 前端生成的唯一ID，用于Redis匹配验证码
}