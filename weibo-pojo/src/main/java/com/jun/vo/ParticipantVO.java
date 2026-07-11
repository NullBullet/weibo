package com.jun.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 参与用户 VO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ParticipantVO implements Serializable {

    private Long userId;

    private String nickname;

    private String avatar;

    private String joinTime;
}