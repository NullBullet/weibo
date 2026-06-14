package com.jun.vo;

import lombok.Data;

/**
 * 简单用户信息 VO（用于通知中的最近点赞用户）
 */
@Data
public class SimpleUserVO {
    private Integer id;
    private String nickname;
    private String avatar;
}
