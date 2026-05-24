package com.jun.vo;

import lombok.Builder;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

@Builder
@Data
public class CommentVO implements Serializable {

    private Long id;

    private Long statusId;

    private String content;

    private LocalDateTime createdAt;

    private Integer likesCount;

    private Boolean isLiked;

    private UserVO user;

    private Long parentId;

    private UserVO replyUser;

    private Integer replyCount;

    private List<CommentVO> replies;
}
