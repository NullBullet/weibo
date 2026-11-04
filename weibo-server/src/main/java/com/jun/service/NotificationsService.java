package com.jun.service;

import com.jun.result.Result;

public interface NotificationsService {
    Result getLikeInfo(String cursor);

    Result getLikeDetail(Integer targetId);

    Result getCommentInfo();

    Result getSystemNotifications(Integer page, Integer pageSize);

    /**
     * 消息中心统一未读数：私信、评论、点赞、系统通知。
     */
    Result getUnreadCount();

    Result markAllCommentNotificationsRead();

    Result markAllLikeNotificationsRead();

    Result markAllSystemNotificationsRead();
}
