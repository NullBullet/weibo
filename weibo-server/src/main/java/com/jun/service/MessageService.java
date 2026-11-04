package com.jun.service;

import com.jun.dto.MessageQueryDTO;
import com.jun.dto.MessageReadDTO;
import com.jun.result.Result;
import com.jun.vo.ChatMessageVO;
import com.jun.vo.MessageSaveResult;

public interface MessageService {
    Result getOrCreatConversation(Long targetUserId);

    Result getConversations();

    Result getConversationDetail(Long conversationId);

    Result getHistory(MessageQueryDTO messageQueryDTO);

    // 新增：保存私信（返回结果区分首次创建与幂等重放）
    MessageSaveResult saveMessage(Long senderId, Long conversationId, String content, String clientMessageId);

    /**
     * 推送已保存的私信事件：向接收者所有在线连接发送 NEW_MESSAGE，并刷新其未读数。
     * 幂等重放不应调用该方法，避免重复推送。
     */
    void pushMessage(ChatMessageVO messageVO);

    Long getUserUnReadCount(Long UserId);

    Result read(MessageReadDTO messageReadDTO);
}
