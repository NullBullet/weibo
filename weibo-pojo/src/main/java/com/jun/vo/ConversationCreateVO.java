package com.jun.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 创建/获取会话返回 VO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConversationCreateVO {
    /**
     * 会话 ID
     */
    private Long conversationId;

    /**
     * 对方用户 ID
     */
    private Long otherUserId;

    /**
     * 对方昵称
     */
    private String nickname;

    /**
     * 对方头像
     */
    private String avatar;
}
