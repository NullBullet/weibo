package com.jun.vo;

import lombok.Data;

@Data
public class SearchUserVO {
    private Long id;
    private String nickname;
    private String avatar;
    private String bio;
    private Boolean isFollowed;
}
