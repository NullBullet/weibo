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

    /**
     * 指定用户的总未读数：私信 + 评论通知 + 点赞通知 + 系统通知。
     */
    Long getTotalUnreadCount(Long userId);

    /**
     * 查询并推送指定用户的总未读数；推送失败仅记录日志，不影响调用方业务流程。
     */
    void pushTotalUnreadCount(Long userId);

    /**
     * 未读数发生变化后（事务提交后）按 MySQL 重算统一未读数缓存并续期 TTL。
     */
    void refreshUnreadCountAfterCommit(Long userId);

    Result markAllCommentNotificationsRead();

    Result markAllLikeNotificationsRead();

    Result markAllSystemNotificationsRead();
}
