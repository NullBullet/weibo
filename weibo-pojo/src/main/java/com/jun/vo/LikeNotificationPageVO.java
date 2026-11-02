package com.jun.vo;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 点赞聚合通知游标分页结果
 */
@Data
public class LikeNotificationPageVO implements Serializable {

    /**
     * 当前页通知（最多 20 条）
     */
    private List<LikeNotificationVO> records;

    /**
     * 下一页游标；hasMore=false 时为 null
     */
    private String nextCursor;

    /**
     * 是否还有下一页
     */
    private Boolean hasMore;
}
