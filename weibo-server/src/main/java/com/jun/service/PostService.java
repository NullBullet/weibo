package com.jun.service;

import com.jun.dto.RepostDTO;
import com.jun.dto.ToggleLikeDTO;
import com.jun.entity.Statuses;
import com.jun.mq.message.RepostMessage;
import com.jun.result.Result;

public interface PostService {
    Result togglePostLike(ToggleLikeDTO toggleLikeDTO);

    Result repost(RepostDTO repostDTO);

    /**
     * 转发落库事务：创建转发微博 + 原微博转发数 +1，由 MQ 消费者调用。
     */
    Statuses saveForwardStatus(RepostMessage message);
}
