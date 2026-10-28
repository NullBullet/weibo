package com.jun.controller.v1.user;

import com.jun.context.BaseContext;
import com.jun.dto.FollowRequestDTO;
import com.jun.result.Result;
import com.jun.service.FollowService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/v1/follows")
public class FollowController {

    @Resource
    private FollowService followService;

    @GetMapping("/check")
    public Result<Boolean> check(Long userId) {
        log.info("是否关注：{}", userId);
        return followService.check(userId);
    }

    @PostMapping("/follow")
    public Result follow(@RequestBody FollowRequestDTO followRequestDTO) {
        log.info("关注：{}", followRequestDTO);
        return followService.follow(BaseContext.getCurrentId(), followRequestDTO.getUserId());
    }

    @PostMapping("/unfollow")
    public Result unfollow(@RequestBody FollowRequestDTO followRequestDTO) {
        log.info("取关：{}", followRequestDTO);
        return followService.unfollow(BaseContext.getCurrentId(), followRequestDTO.getUserId());
    }
}
