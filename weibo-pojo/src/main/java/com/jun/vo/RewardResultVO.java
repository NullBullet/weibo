package com.jun.vo;

import lombok.Builder;
import lombok.Data;

/**
 * 抽奖参与结果 WebSocket 推送负载（type = REWARD_RESULT）。
 * 仅承载 MQ 异步落库的最终结果：参与记录落库成功推送 JOINED，
 * 落库失败（消费者重试耗尽进入死信）推送 SAVE_FAILED。
 * 同步校验阶段的拒绝原因（未开始/已结束/非粉丝/重复/名额满）不通过本消息推送，
 * 而是随 POST /v1/post/repost 的 RepostVO.reward（RewardCheckVO）同步返回，
 * 避免前端在 HTTP 响应与 WebSocket 之间双路处理同一校验结果。
 */
@Data
@Builder
public class RewardResultVO {

    public static final String TYPE = "REWARD_RESULT";

    /** 参与记录已落库（本次转发参与成功） */
    public static final String REASON_JOINED = "JOINED";
    /** 参与记录落库失败（消费者重试耗尽进入死信队列） */
    public static final String REASON_SAVE_FAILED = "SAVE_FAILED";

    /** 消息类型标识 */
    private String type;

    /** 活动 ID */
    private Long activityId;

    /** 抽奖活动关联的微博 ID（原微博） */
    private Long statusId;

    /** 本次转发是否成功参与（参与记录新写入并已落库） */
    private Boolean joined;

    /** 结果原因，见 REASON_* 常量 */
    private String reason;
}
