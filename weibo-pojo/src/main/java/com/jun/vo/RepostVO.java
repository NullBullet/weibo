package com.jun.vo;

import lombok.Builder;
import lombok.Data;

/**
 * 转发接口同步返回载荷（POST /v1/post/repost）。
 * 转发微博落库为 MQ 异步执行，本载荷只代表请求已受理，
 * 落库结果由 WebSocket 的 REPOST_RESULT 通知确认。
 */
@Data
@Builder
public class RepostVO {

    /** 被转发微博 ID（转发链已解析到原微博），与 REPOST_RESULT 中的 originalStatusId 一致 */
    private Long originalStatusId;

    /** 抽奖参与同步校验结果；该微博没有抽奖活动时为 null */
    private RewardCheckVO reward;
}
