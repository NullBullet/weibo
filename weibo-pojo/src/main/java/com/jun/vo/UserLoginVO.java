package com.jun.vo;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class UserLoginVO {
    private String userId;
    private String token;
}