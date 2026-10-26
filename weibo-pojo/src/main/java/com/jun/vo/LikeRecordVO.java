package com.jun.vo;

import lombok.Data;

/**
 * 点赞记录（attitudes 表 target_id + user_id），仅供点赞集合缓存重建/兜底使用
 */
@Data
public class LikeRecordVO {
    private Long targetId;
    private Long userId;
}
