package com.jun.controller.v1.user;

import com.jun.result.Result;
import com.jun.service.NotificationsService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

@RequestMapping("/v1/notifications")
@RestController
@Slf4j
public class NotificationsController {

    @Resource
    private NotificationsService notificationsService;

    @GetMapping("/likes")
    public Result like(@RequestParam(required = false) String cursor) {
        log.info("获取当前用户收到的点赞聚合通知列表, cursor={}", cursor);
        return notificationsService.getLikeInfo(cursor);
    }

    @GetMapping("/likes/{targetId}")
    public Result likeDetail(@PathVariable Integer targetId) {
        log.info("获取{}的点赞用户详情", targetId);
        return notificationsService.getLikeDetail(targetId);
    }

    @GetMapping("/comments")
    public Result comment() {
        log.info("获取当前用户收到的评论列表");
        return notificationsService.getCommentInfo();
    }

    @GetMapping("/system")
    public Result systemNotifications(@RequestParam(required = false, defaultValue = "1") Integer page,
                                      @RequestParam(required = false, defaultValue = "20") Integer pageSize) {
        log.info("获取系统通知列表: page={}, pageSize={}", page, pageSize);
        return notificationsService.getSystemNotifications(page, pageSize);
    }

    @PutMapping("/system/read-all")
    public Result markAllSystemRead() {
        log.info("标记全部系统通知已读");
        return notificationsService.markAllSystemNotificationsRead();
    }
}
