package com.jun.controller.v1.user;

import com.jun.dto.FollowQueryDTO;
import com.jun.dto.ScrollDTO;
import com.jun.dto.StatusPageQueryDTO;
import com.jun.dto.UserProfileDTO;
import com.jun.result.PageResult;
import com.jun.result.Result;
import com.jun.result.ScrollResult;
import com.jun.service.UsersService;
import com.jun.vo.UsersInfoVO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@Slf4j
@RestController
@RequestMapping("/v1/users")
public class UsersController {
    @Resource
    private UsersService usersService;

    @GetMapping("/me")
    public Result<UsersInfoVO> me() {
        log.info("获取用户详细信息（含统计数据）");
        return usersService.me();
    }

    @PutMapping("/me/profile")
    public Result<String> profile(UserProfileDTO userProfileDTO, MultipartFile avatarFile) {
        log.info("更新用户资料{}", userProfileDTO);
        return usersService.updateProfile(userProfileDTO, avatarFile);
    }

    @GetMapping("/me/profile")
    public Result<UserProfileDTO> profile() {
        log.info("获取用户资料");
        return usersService.getProfile();
    }

    @GetMapping("/me/posts")
    public Result<PageResult> posts(StatusPageQueryDTO StatusPageQueryDTO) {
        log.info("获取用户个人帖子记录{}", StatusPageQueryDTO);
        return usersService.getPost(StatusPageQueryDTO);
    }

    @GetMapping("/{userId}")
    public Result<UsersInfoVO> userInfo(@PathVariable Long userId) {
        log.info("获取指定用户信息:{}", userId);
        return usersService.getUserInfo(userId);
    }

    @GetMapping("/{userId}/posts")
    public Result<ScrollResult> userPosts(@PathVariable Long userId, ScrollDTO scrollDTO) {
        log.info("获取指定用户帖子列表:{}", userId);
        return usersService.getUserPosts(userId, scrollDTO);
    }

    @GetMapping("/following")
    public Result<PageResult> following(FollowQueryDTO dto) {
        log.info("获取关注列表: {}", dto);
        return usersService.getFollowingList(dto);
    }

    @GetMapping("/followers")
    public Result<PageResult> followers(FollowQueryDTO dto) {
        log.info("获取粉丝列表: {}", dto);
        return usersService.getFollowerList(dto);
    }

    @GetMapping("/following/active")
    public Result<PageResult> followingActive(FollowQueryDTO dto) {
        log.info("获取最新发博关注列表: {}", dto);
        return usersService.getFollowingActiveList(dto);
    }
}
