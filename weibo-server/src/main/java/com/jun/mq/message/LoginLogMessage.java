package com.jun.mq.message;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LoginLogMessage {

    /**
     * MQ消息唯一ID（幂等去重）
     */
    private String messageId;

    /**
     * 用户ID（登录失败且用户不存在时为 null）
     */
    private Long userId;

    /**
     * 用户名（用于参考）
     */
    private String username;

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
     * 失败原因（登录成功时为 null）
     */
    private String failReason;

    /**
     * 重试次数
     */
    private Integer retryCount;
}
