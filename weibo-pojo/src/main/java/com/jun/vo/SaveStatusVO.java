package com.jun.vo;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 微博实体类
 */
@Data
public class SaveStatusVO implements Serializable {
    private Long id;
    private String content;
    private LocalDateTime createdAt;
}
