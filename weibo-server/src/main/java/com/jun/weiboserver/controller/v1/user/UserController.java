package com.jun.weiboserver.controller.v1.user;

import com.jun.entity.User;
import com.jun.vo.UserInfoVO;
import com.jun.weiboserver.service.UserService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.jun.result.Result;

@Slf4j
@RestController
@RequestMapping("/v1/user")
public class UserController {

    @Resource
    private UserService userService;

    //保留接口
    @GetMapping("/me")
    public Result<UserInfoVO> me(){
        log.info("获取当前登录用户信息");
        return userService.userInfoById();
    }

}
