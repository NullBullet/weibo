package com.jun.entity;

import lombok.Data;
import java.time.LocalDateTime;

/**
 * 评论表 Entity
 */
@Data
public class Comment {
    private Long id;
    private Long weiboId;
    private Long userId;
    private Long parentId; // 父评论 ID (0 表示一级评论)
    private String content;
    private Integer likeCount;
    private Integer replyCount;
    private Integer status; // 0-删除 1-正常
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}