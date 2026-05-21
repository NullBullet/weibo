package com.jun.vo;

import com.jun.entity.StatusImage;
import com.jun.entity.User;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 微博实体类
 */
@Data
public class HotStatusVO implements Serializable {

    /**
     * 微博 ID
     */
    private Long id;

    /**
     * 用户 ID
     */
    private Long userId;

    /**
     * 当前用户是否点赞该帖子
     */
    private Boolean isLiked;

    /**
     * 当前用户是否关注帖子用户
     */
    private Boolean isFollowed;

    /**
     * 微博内容
     */
    private String content;

    /**
     * 微博类型：0-普通，1-转发，2-回复
     */
    private Integer statusType;

    /**
     * 原微博 ID（转发/回复时）
     */
    private Long originalStatusId;

    /**
     * 是否置顶：0-否，1-是
     */
    private Integer isTop;

    /**
     * 是否删除：0-否，1-是
     */
    private Integer isDeleted;

    /**
     * 评论数
     */
    private Integer commentsCount;

    /**
     * 点赞数
     */
    private Integer attitudesCount;

    /**
     * 转发数
     */
    private Integer repostsCount;

    /**
     * 浏览量
     */
    private Integer viewsCount;

    /**
     * 创建时间
     */
    private LocalDateTime createdAt;

    /**
     * 更新时间
     */
    private LocalDateTime updatedAt;

    private UserVO user;

    /** 图片列表 (核心：将 StatusImage 转换为 URL 列表) */
    private List<StatusImage> images;

    /** 原微博（转发时使用） */
    private HotStatusVO originalStatus;
}
