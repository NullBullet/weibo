package com.jun.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.jun.enumeration.TargetType;
import lombok.Data;

import java.io.Serializable;

@Data
public class ToggleLikeDTO implements Serializable {
    private TargetType targetType;
    private Long targetId;
    private Boolean isLiked;

    @JsonIgnore
    private Long userId;

    /**
     * MyBatis 用的 targetType 值（传给 attitudes 表的 target_type 字段）
     */
    public Integer getTargetTypeValue() {
        return targetType != null ? targetType.getCode() : null;
    }
}
