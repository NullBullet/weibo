package com.jun.vo;

import lombok.Data;

import java.util.List;

/**
 * 点赞聚合通知 VO
 */
@Data
public class LikeNotificationVO {
    private Integer notificationId;
    private String targetType;
    private Integer targetId;
    private String content;
    private Integer likeCount;
    private List<SimpleUserVO> recentUsers;
    private String lastLikeTime;
}
