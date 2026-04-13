package com.jun.entity;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 系统配置实体类
 */
@Data
public class SystemConfiguration implements Serializable {

    /**
     * 配置 ID
     */
    private Long id;

    /**
     * 配置键
     */
    private String configKey;

    /**
     * 配置值
     */
    private String configValue;

    /**
     * 配置类型：0-字符串，1-数字，2-布尔，3-JSON
     */
    private Integer configType;

    /**
     * 配置说明
     */
    private String description;

    /**
     * 更新时间
     */
    private LocalDateTime updatedAt;
}
