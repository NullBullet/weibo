package com.jun.weiboserver.service.impl;

import com.jun.entity.User;
import com.jun.result.Result;
import com.jun.vo.UserInfoVO;
import com.jun.weiboserver.mapper.UserMapper;
import com.jun.weiboserver.service.UserService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import com.jun.context.BaseContext;

@Service
public class UserServiceImpl implements UserService {
    @Resource
    private UserMapper userMapper;

    @Override
    public Result<UserInfoVO> userInfoById() {
        Long userId = BaseContext.getCurrentId();
        UserInfoVO userInfoVO = userMapper.getById(userId);
        return Result.success(userInfoVO);
    }
}
