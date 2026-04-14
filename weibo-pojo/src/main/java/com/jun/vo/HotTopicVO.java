package com.jun.vo;

import lombok.Data;

@Data
public class HotTopicVO {
    /**
     * 排名
     * 对应 JSON 中的 "rank"
     */
    private Integer rank;

    /**
     * 话题标题
     * 对应 JSON 中的 "title"
     */
    private String title;

    /**
     * 热度值
     * 对应 JSON 中的 "heat"
     */

    private Long heat;
}