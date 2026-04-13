package com.jun.entity;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 搜索历史实体类
 */
@Data
public class SearchHistory implements Serializable {

    /**
     * 历史 ID
     */
    private Long id;

    /**
     * 用户 ID（未登录为 NULL）
     */
    private Long userId;

    /**
     * 搜索关键词
     */
    private String keyword;

    /**
     * 搜索类型：0-全部，1-微博，2-用户，3-话题
     */
    private Integer searchType;

    /**
     * 搜索结果数
     */
    private Integer resultCount;

    /**
     * 搜索时间
     */
    private LocalDateTime createdAt;
}
