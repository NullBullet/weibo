package com.jun.weiboserver.controller.v1.user;

import com.jun.result.Result;
import com.jun.vo.UsersInfoVO;
import com.jun.weiboserver.service.UsersService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/v1/users")
public class UsersController {
    @Resource
    private UsersService usersService;

    @GetMapping("/me")
    public Result<UsersInfoVO> me(){
        log.info("获取用户详细信息（含统计数据）");
        return usersService.me();
    }


}
