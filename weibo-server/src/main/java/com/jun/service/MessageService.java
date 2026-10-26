package com.jun.service;

import com.jun.dto.MessageQueryDTO;
import com.jun.dto.MessageReadDTO;
import com.jun.result.Result;
import com.jun.vo.MessageSaveResult;

public interface MessageService {
    Result getOrCreatConversation(Long targetUserId);

    Result getConversations();

    Result getConversationDetail(Long conversationId);

    Result getHistory(MessageQueryDTO messageQueryDTO);

    // 新增：保存私信（返回结果区分首次创建与幂等重放）
    MessageSaveResult saveMessage(Long senderId, Long conversationId, String content, String clientMessageId);

    Long getUserUnReadCount(Long UserId);

    Result read(MessageReadDTO messageReadDTO);
}
