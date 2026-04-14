package com.jun.weiboserver.service.impl;

import com.jun.entity.User;
import com.jun.entity.UserStatistics;
import com.jun.result.Result;
import com.jun.vo.UserCountVO;
import com.jun.vo.UsersInfoVO;
import com.jun.weiboserver.mapper.UsersMapper;
import com.jun.weiboserver.service.UsersService;
import com.jun.context.BaseContext;
import jakarta.annotation.Resource;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

@Service
public class UsersServiceImpl implements UsersService {

    @Resource
    private UsersMapper usersMapper;

    @Override
    public Result<UsersInfoVO> me() {
        Long userId = BaseContext.getCurrentId();
        User user = usersMapper.getUserInfoByUserId(userId);
        UsersInfoVO usersInfoVO = new UsersInfoVO();

        UserCountVO userCountVO = usersMapper.countByUserId(userId);

        BeanUtils.copyProperties(user, usersInfoVO);

        usersInfoVO.setStats(userCountVO);
        return Result.success(usersInfoVO);
    }
}
