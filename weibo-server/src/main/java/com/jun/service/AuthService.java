package com.jun.service;

import com.jun.dto.UserLoginDTO;
import com.jun.result.Result;
import com.jun.vo.UserLoginVO;

public interface AuthService {
    Result<UserLoginVO> login(UserLoginDTO userLoginDTO);
}
