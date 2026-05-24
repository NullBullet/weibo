package com.jun.controller.v1.user;

import com.jun.dto.CommentsResultDTO;
import com.jun.dto.PostCommentsDTO;
import com.jun.dto.RepliesCommentsResultDTO;
import com.jun.result.Result;
import com.jun.service.CommentsService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/v1/comments")
public class CommentsController {
    @Resource
    private CommentsService commentsService;

    @GetMapping
    public Result getComments(CommentsResultDTO commentsResultDTO){
        log.info("获取评论：{}",commentsResultDTO);
        return commentsService.getComments(commentsResultDTO);
    }

    @PostMapping
    public Result postComments(@RequestBody PostCommentsDTO postCommentsDTO){
        log.info("发评论");
        return commentsService.postComments(postCommentsDTO);
    }

    @GetMapping("/{commentId}/replies")
    public Result getReplies(@PathVariable Long commentId,
                          @RequestParam(defaultValue = "1") Integer page,
                          @RequestParam(defaultValue = "10") Integer pageSize){
        log.info("获取二级评论");
        RepliesCommentsResultDTO repliesCommentsResultDTO = RepliesCommentsResultDTO.builder()
                .commentId(commentId)
                .page(page)
                .pageSize(pageSize).build();
        return commentsService.getReplies(repliesCommentsResultDTO);
    }
}
