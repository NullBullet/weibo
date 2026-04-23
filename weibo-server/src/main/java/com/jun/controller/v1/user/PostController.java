package com.jun.controller.v1.user;

import com.jun.dto.ToggleLikeDTO;
import com.jun.result.Result;
import com.jun.service.PostService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RequestMapping("/v1/post")
@RestController
public class PostController {

    @Resource
    private PostService postService;

    @PostMapping("/toggleLike")
    public Result toggleLike(@RequestBody ToggleLikeDTO toggleLikeDTO){
        log.info("点赞/取消点赞微博:{}",toggleLikeDTO);
        return postService.toggleLike(toggleLikeDTO);
    }

}
