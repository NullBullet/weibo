package com.jun.service.impl;

import com.jun.config.RabbitMQConfig;
import com.jun.constant.MessageConstant;
import com.jun.dto.UserLoginDTO;
import com.jun.entity.User;
import com.jun.exception.AccountLockedException;
import com.jun.exception.AccountNotFoundException;
import com.jun.exception.PasswordErrorException;
import com.jun.mq.message.LoginLogMessage;
import com.jun.properties.JwtProperties;
import com.jun.result.Result;
import com.jun.utils.JwtUtil;
import com.jun.vo.UserLoginVO;
import com.jun.mapper.AuthMapper;
import com.jun.service.AuthService;
import jakarta.annotation.Resource;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.DigestUtils;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static com.jun.constant.JwtClaimsConstant.USER_ID;
import static com.jun.constant.MessageConstant.ACCOUNT_NOT_FOUND;
import static com.jun.constant.MessageConstant.PASSWORD_ERROR;
import static com.jun.constant.StatusConstant.ENABLE;

@Service
public class AuthServiceImpl implements AuthService {

    @Resource
    private AuthMapper authMapper;
    @Resource
    private JwtProperties jwtProperties;
    @Resource
    private RabbitTemplate rabbitTemplate;

    @Override
    public Result<UserLoginVO> login(UserLoginDTO userLoginDTO) {
        String username = userLoginDTO.getUsername();
        String password = userLoginDTO.getPassword();

        //验证账号存在
        User user = authMapper.getByUsername(username);
        if (user == null) {
            sendLoginLog(null, username, userLoginDTO.getIpAddress(),
                    userLoginDTO.getUserAgent(), 0, "账号不存在");
            throw new AccountNotFoundException(ACCOUNT_NOT_FOUND);
        }

        password = DigestUtils.md5DigestAsHex(password.getBytes());
        //验证账号密码
        if (!password.equals(user.getPassword())) {
            sendLoginLog(user.getId(), username, userLoginDTO.getIpAddress(),
                    userLoginDTO.getUserAgent(), 0, "密码错误");
            throw new PasswordErrorException(PASSWORD_ERROR);
        }

        //验证账号状态
        if (!Objects.equals(user.getStatus(), ENABLE)) {
            sendLoginLog(user.getId(), username, userLoginDTO.getIpAddress(),
                    userLoginDTO.getUserAgent(), 0, "账号被锁定");
            throw new AccountLockedException(MessageConstant.ACCOUNT_LOCKED);
        }

        Map<String, Object> claims = new HashMap<>();
        claims.put(USER_ID, user.getId());
        String token = JwtUtil.createJWT(
                jwtProperties.getUserSecretKey(),
                jwtProperties.getUserTtl(),
                claims);

        //登录成功日志
        sendLoginLog(user.getId(), username, userLoginDTO.getIpAddress(),
                userLoginDTO.getUserAgent(), 1, null);

        UserLoginVO userLoginVO = UserLoginVO.builder()
                .userId(user.getId().toString())
                .token(token).build();
        return Result.success(userLoginVO);
    }

    /**
     * 发送登录日志 MQ 消息（异步落库）
     */
    private void sendLoginLog(Long userId, String username, String ipAddress,
                              String userAgent, Integer loginStatus, String failReason) {
        long now = System.currentTimeMillis();

        LoginLogMessage message = LoginLogMessage.builder()
                .messageId(UUID.randomUUID().toString())
                .userId(userId)
                .username(username)
                .loginType(1) // 密码登录
                .ipAddress(ipAddress)
                .userAgent(userAgent)
                .loginStatus(loginStatus)
                .failReason(failReason)
                .activeTimestamp(now)
                .retryCount(0)
                .build();

        CorrelationData correlationData = new CorrelationData(message.getMessageId());

        rabbitTemplate.convertAndSend(
                RabbitMQConfig.WEIBO_TOPIC_EXCHANGE,
                RabbitMQConfig.LOGIN_LOG,
                message,
                correlationData
        );
    }
}
