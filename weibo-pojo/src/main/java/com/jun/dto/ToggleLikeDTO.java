package com.jun.dto;

import lombok.Data;

import java.io.Serializable;

@Data
public class ToggleLikeDTO implements Serializable {
    private Integer targetType;
    private Long targetId;
    private Boolean isLiked;
    private Long userId;
}
