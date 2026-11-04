package com.jun.vo;

import lombok.Data;

/**
 * 消息中心统一未读数：私信、评论、点赞、系统通知
 */
@Data
public class UnreadCountVO {

    private Long chat;

    private Long comment;

    private Long like;

    private Long system;
}
