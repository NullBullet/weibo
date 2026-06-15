package com.jun.controller.v1.user;

import com.jun.result.Result;
import com.jun.service.NotificationsService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RequestMapping("/v1/notifications")
@RestController
@Slf4j
public class NotificationsController {

    @Resource
    private NotificationsService notificationsService;

    //todo:Redis缓存未读数、Redis缓存最近点赞用户
    @GetMapping("/likes")
    public Result like(){
        log.info("获取当前用户的获取当前用户收到的点赞聚合通知列表");
        return notificationsService.getLikeInfo();
    }

    @GetMapping("/likes/{targetId}")
    public Result likeDetail(@PathVariable Integer targetId){
        log.info("获取{}的点赞用户详情",targetId);
        return notificationsService.getLikeDetail(targetId);
    }

    @GetMapping("/comments")
    public Result comment(){
        log.info("获取当前用户收到的评论列表");
        return notificationsService.getCommentInfo();
    }
}
