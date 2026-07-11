package com.jun.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 中奖用户 VO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WinnerVO implements Serializable {

    private Long userId;

    private String nickname;

    private String avatar;
}