package com.jun.vo;

import lombok.Data;

/**
 * 验证码 VO
 */
@Data
public class CaptchaVO {
    private String captchaId;
    private String image;
}
