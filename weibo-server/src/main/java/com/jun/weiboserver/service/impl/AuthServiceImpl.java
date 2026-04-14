package com.jun.weiboserver.service.impl;

import com.jun.constant.MessageConstant;
import com.jun.dto.UserLoginDTO;
import com.jun.entity.User;
import com.jun.exception.AccountLockedException;
import com.jun.exception.AccountNotFoundException;
import com.jun.exception.PasswordErrorException;
import com.jun.properties.JwtProperties;
import com.jun.result.Result;
import com.jun.utils.JwtUtil;
import com.jun.vo.UserLoginVO;
import com.jun.weiboserver.mapper.AuthMapper;
import com.jun.weiboserver.service.AuthService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.util.DigestUtils;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

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

    @Override
    public Result<UserLoginVO> login(UserLoginDTO userLoginDTO) {
        String username = userLoginDTO.getUsername();
        String password = userLoginDTO.getPassword();
        User user = authMapper.getByUsername(username);
        //验证账号存在
        if (user == null) {
            throw new AccountNotFoundException(ACCOUNT_NOT_FOUND);
        }

        password = DigestUtils.md5DigestAsHex(password.getBytes());
        //验证账号密码
        if (!password.equals(user.getPassword())) {
            throw new PasswordErrorException(PASSWORD_ERROR);
        }

        //验证账号状态
        if (!Objects.equals(user.getStatus(), ENABLE)) {
            throw new AccountLockedException(MessageConstant.ACCOUNT_LOCKED);
        }

        Map<String, Object> claims = new HashMap<>();
        claims.put(USER_ID, user.getId());
        String token = JwtUtil.createJWT(
                jwtProperties.getUserSecretKey(),
                jwtProperties.getUserTtl(),
                claims);

        UserLoginVO userLoginVO = UserLoginVO.builder()
                .userId(user.getId().toString())
                .token(token).build();
        return Result.success(userLoginVO);
    }
}
