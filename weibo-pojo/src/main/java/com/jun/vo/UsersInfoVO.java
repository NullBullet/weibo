package com.jun.vo;

import lombok.Data;

@Data
public class UsersInfoVO {
    private Long id;
    private String username;
    private String avatar;
    private Object stats; //following_count followers_count likes_count
    private String bio;
}
