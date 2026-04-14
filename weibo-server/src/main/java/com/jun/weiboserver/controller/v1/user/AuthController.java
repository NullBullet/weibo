package com.jun.weiboserver.controller.v1.user;

import com.jun.dto.UserLoginDTO;
import com.jun.vo.UserLoginVO;
import com.jun.weiboserver.service.AuthService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.jun.result.Result;

@Slf4j
@RequestMapping("/v1/auth")
@RestController
public class AuthController {

    @Resource
    private AuthService authService;

    @PostMapping("/login")
    public Result<UserLoginVO> login(@RequestBody UserLoginDTO userLoginDTO){
        log.info("用户登录：{}",userLoginDTO);
        return authService.login(userLoginDTO);
    }

    @PostMapping("/logout")
    public Result logout(){
        log.info("用户登出");
        return Result.success();
    }
}
