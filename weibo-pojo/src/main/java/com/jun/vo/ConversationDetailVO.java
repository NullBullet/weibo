package com.jun.vo;

import lombok.Data;

/**
 * 会话详情 VO
 */
@Data
public class ConversationDetailVO {
    private Long conversationId;
    private TargetUser targetUser;

    @Data
    public static class TargetUser {
        private Long id;
        private String nickname;
        private String avatar;
    }
}
