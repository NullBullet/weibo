package com.jun.service;

import com.jun.dto.UserLoginDTO;
import com.jun.vo.UserLoginVO;

public interface AuthService {
    com.jun.result.Result<UserLoginVO> login(UserLoginDTO userLoginDTO);
}
