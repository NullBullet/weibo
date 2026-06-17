package com.jun.service;

import com.jun.dto.RegisterDTO;
import com.jun.dto.UserLoginDTO;
import com.jun.result.Result;
import com.jun.vo.UserLoginVO;

public interface AuthService {
    Result<UserLoginVO> login(UserLoginDTO userLoginDTO);

    Result captcha(String ip);

    Result register(RegisterDTO registerDTO, String ipAddress, String userAgent);
}
