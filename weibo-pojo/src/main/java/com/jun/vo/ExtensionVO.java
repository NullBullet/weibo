package com.jun.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 帖子扩展信息 VO
 * 用于在帖子中附加关联业务信息（如抽奖活动等），方便前端展示
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExtensionVO implements Serializable {

    /**
     * 扩展类型：REWARD - 抽奖活动
     */
    private String type;

    /**
     * 关联业务 ID
     */
    private Long id;
}