package com.jun.service;

import com.jun.vo.RewardCheckVO;

public interface ActivityService {
    /**
     * 处理转发后的抽奖参与，返回同步校验结果。
     * 该微博没有抽奖活动时返回 null；校验通过（已提交 MQ 落库）时 state = PENDING。
     */
    RewardCheckVO handleForwardActivity(Long originalStatusId, Long currentId, Long forwardStatusId);
}
