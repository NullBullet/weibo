package com.jun.vo;

import lombok.Data;

@Data
public class UserCountVO {
    private Integer followingCount;
    private Integer followersCount;
    private Integer likesCount;
}
