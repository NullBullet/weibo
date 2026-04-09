package com.jun.entity;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class WeiboCount {
    private Long weiboId;
    private Integer likeCount;
    private Integer commentCount;
    private Integer repostCount;
    private Integer collectCount;
    private LocalDateTime updateTime;
}