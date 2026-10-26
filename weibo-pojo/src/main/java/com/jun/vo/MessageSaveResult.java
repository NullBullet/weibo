package com.jun.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 私信保存结果：区分首次创建与幂等重放。
 * replay=true 表示该 clientMessageId 的消息此前已保存并推送过，
 * 调用方不得再次向接收者推送 NEW_MESSAGE 或未读数。
 */
@Data
@AllArgsConstructor
public class MessageSaveResult {

    /**
     * 保存后的消息（首次创建的新消息，或重放命中的原消息）
     */
    private ChatMessageVO message;

    /**
     * 是否为幂等重放
     */
    private boolean replay;

    public static MessageSaveResult created(ChatMessageVO message) {
        return new MessageSaveResult(message, false);
    }

    public static MessageSaveResult replayed(ChatMessageVO message) {
        return new MessageSaveResult(message, true);
    }
}
