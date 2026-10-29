package com.jun.vo;

import lombok.Builder;
import lombok.Data;

/**
 * 转发落库结果 WebSocket 推送负载（type = REPOST_RESULT）。
 * 转发为 MQ 异步落库，HTTP 返回值不代表落库结果，
 * 前端以本通知为准确认转发是否成功（失败场景：消费者重试耗尽进入死信队列）。
 */
@Data
@Builder
public class RepostResultVO {

    public static final String TYPE = "REPOST_RESULT";

    /** 消息类型标识 */
    private String type;

    /** 转发是否落库成功 */
    private Boolean success;

    /** 被转发微博 ID（转发链已解析到原微博） */
    private Long originalStatusId;

    /** 新转发微博 ID，失败时为 null */
    private Long forwardStatusId;
}
