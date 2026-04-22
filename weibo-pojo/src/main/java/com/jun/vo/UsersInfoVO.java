package com.jun.vo;

import lombok.Data;

@Data
public class UsersInfoVO {
    private Long id;
    private String username;
    private String avatar;
    private Object stats; //following_count followers_count likes_count
    private String bio;
    /**
     * 用户类型：0-普通用户，1-大V用户
     */
    private Integer userType;
}
