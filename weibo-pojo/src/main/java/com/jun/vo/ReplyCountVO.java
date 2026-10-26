package com.jun.vo;

import lombok.Data;

@Data
public class ReplyCountVO {
    private Long commentId;
    private Integer count;
    /** 评论点赞数（comments.likes_count），仅供统计缓存重建/兜底使用 */
    private Integer likesCount;
}
