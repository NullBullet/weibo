package com.jun.service;

import com.jun.result.Result;

public interface NotificationsService {
    Result getLikeInfo();

    Result getLikeDetail(Integer targetId);

    Result getCommentInfo();

    Result getSystemNotifications(Integer page, Integer pageSize);

    Result markAllSystemNotificationsRead();
}
