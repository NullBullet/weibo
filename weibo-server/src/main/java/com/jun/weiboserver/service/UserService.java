package com.jun.weiboserver.service;
import com.jun.entity.User;
import com.jun.result.Result;
import com.jun.vo.UserInfoVO;

public interface UserService {
    Result<UserInfoVO> userInfoById();
}
