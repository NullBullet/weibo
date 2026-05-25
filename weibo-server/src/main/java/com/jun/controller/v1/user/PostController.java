package com.jun.controller.v1.user;

import com.jun.dto.RepostDTO;
import com.jun.dto.ToggleLikeDTO;
import com.jun.enumeration.TargetType;
import com.jun.result.Result;
import com.jun.service.CommentsService;
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
    @Resource
    private CommentsService commentsService;

    @PostMapping("/toggleLike")
    public Result toggleLike(@RequestBody ToggleLikeDTO toggleLikeDTO) {
        TargetType type = toggleLikeDTO.getTargetType();
        switch (type) {
            case POST:
                log.info("点赞/取消点赞微博:{}", toggleLikeDTO);
                return postService.togglePostLike(toggleLikeDTO);
            case COMMENT:
                log.info("点赞/取消点赞评论:{}", toggleLikeDTO);
                return commentsService.toggleCommentLike(toggleLikeDTO);
        }
        return Result.error("不支持的 targetType");
    }

    @PostMapping("/repost")
    public Result repost(@RequestBody RepostDTO repostDTO) {
        log.info("转发微博：{}", repostDTO);
        return postService.repost(repostDTO);
    }
}
