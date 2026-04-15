package com.jun.weiboserver.controller.v1.user;

import com.jun.dto.UserProfileDTO;
import com.jun.result.Result;
import com.jun.vo.UsersInfoVO;
import com.jun.weiboserver.service.UsersService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.HashMap;
import java.util.Map;

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

    @PutMapping("/me/profile")
    public Result<String> profile(UserProfileDTO userProfileDTO,MultipartFile avatarFile){
        log.info("更新用户资料{}",userProfileDTO);
        return usersService.updateProfile(userProfileDTO,avatarFile);
    }

    @GetMapping("/me/profile")
    public Result<UserProfileDTO> profile(){
        log.info("获取用户资料");
        return usersService.getProfile();
    }
}
