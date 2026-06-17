package com.jun.vo;

import lombok.Data;

/**
 * 关注列表用户 VO
 */
@Data
public class FollowUserVO {
    private Long id;
    private String username;
    private String nickname;
    private String avatar;
    private String bio;
    private Boolean isFollowed;
}
