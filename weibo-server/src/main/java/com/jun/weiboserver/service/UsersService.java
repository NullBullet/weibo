package com.jun.weiboserver.service;
import com.jun.result.Result;
import com.jun.vo.UsersInfoVO;

public interface UsersService {
    Result<UsersInfoVO> me();
}
