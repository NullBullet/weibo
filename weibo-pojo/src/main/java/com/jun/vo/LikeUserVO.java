package com.jun.vo;

import lombok.Data;

/**
 * 点赞用户详情 VO
 */
@Data
public class LikeUserVO {
    private Integer id;
    private String nickname;
    private String avatar;
    private String likeTime;
}
