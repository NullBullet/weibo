package com.jun.vo;

import lombok.Builder;
import lombok.Data;

import java.io.Serializable;

@Builder
@Data
public class ToggleLikeVO implements Serializable {
    private Long likeCount;
    private Boolean isLiked;
}
