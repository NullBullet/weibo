package com.jun.controller.v1.user;

import com.jun.dto.RegisterDTO;
import com.jun.dto.UserLoginDTO;
import com.jun.result.Result;
import com.jun.service.AuthService;
import com.jun.utils.IpUtil;
import com.jun.vo.UserLoginVO;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RequestMapping("/v1/auth")
@RestController
public class AuthController {

    @Resource
    private AuthService authService;

    @Resource
    private HttpServletRequest request;

    @PostMapping("/login")
    public Result<UserLoginVO> login(@RequestBody UserLoginDTO userLoginDTO) {
        log.info("用户登录：{}", userLoginDTO);

        // 注入请求信息
        userLoginDTO.setIpAddress(IpUtil.getClientIp(request));
        userLoginDTO.setUserAgent(request.getHeader("User-Agent"));

        return authService.login(userLoginDTO);
    }

    @GetMapping("/captcha")
    public Result captcha() {
        String ip = IpUtil.getClientIp(request);
        return authService.captcha(ip);
    }

    @PostMapping("/register")
    public Result register(@RequestBody RegisterDTO registerDTO) {
        log.info("用户注册：{}", registerDTO.getUsername());
        String ip = IpUtil.getClientIp(request);
        String userAgent = request.getHeader("User-Agent");
        return authService.register(registerDTO, ip, userAgent);
    }

    @PostMapping("/logout")
    public Result logout() {
        log.info("用户登出");
        return Result.success();
    }
}
