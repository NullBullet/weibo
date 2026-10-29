package com.jun.vo;

import lombok.Builder;
import lombok.Data;

/**
 * 转发抽奖参与同步校验结果（随 POST /v1/post/repost 同步返回）。
 * 未通过校验的用户不会进入 MQ 落库，前端直接使用本结果提示；
 * state = PENDING 表示已获得参与资格、参与记录已提交 MQ 等待落库，
 * 最终落库结果由 WebSocket 的 REWARD_RESULT 通知确认。
 */
@Data
@Builder
public class RewardCheckVO {

    /** 校验通过，参与记录已提交 MQ、等待落库 */
    public static final String STATE_PENDING = "PENDING";
    /** 此前已参与过该活动 */
    public static final String STATE_JOINED = "JOINED";
    /** 限量模式名额已满 */
    public static final String STATE_FULL = "FULL";
    /** 活动已结束 */
    public static final String STATE_ENDED = "ENDED";
    /** 活动尚未开始 */
    public static final String STATE_NOT_STARTED = "NOT_STARTED";
    /** 仅粉丝可参与且当前用户未关注活动创建者 */
    public static final String STATE_NOT_FAN = "NOT_FAN";

    /** 活动 ID */
    private Long activityId;

    /** 抽奖活动关联的微博 ID（原微博） */
    private Long statusId;

    /** 同步校验结果，见 STATE_* 常量 */
    private String state;
}
