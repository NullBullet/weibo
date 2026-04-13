package com.jun.entity;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 热搜话题实体类
 */
@Data
public class HotTopic implements Serializable {

    /**
     * 话题 ID
     */
    private Long id;

    /**
     * 话题标题
     */
    private String title;

    /**
     * 热度值
     */
    private Long heatValue;

    /**
     * 排名
     */
    private Integer rank;

    /**
     * 话题类型：0-普通，1-广告
     */
    private Integer topicType;

    /**
     * 图标标识（如：热、新、爆）
     */
    private String icon;

    /**
     * 状态：0-下线，1-上线
     */
    private Integer status;

    /**
     * 开始时间
     */
    private LocalDateTime startTime;

    /**
     * 结束时间
     */
    private LocalDateTime endTime;

    /**
     * 创建时间
     */
    private LocalDateTime createdAt;

    /**
     * 更新时间
     */
    private LocalDateTime updatedAt;
}
