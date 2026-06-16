package com.jun.service;

import com.jun.dto.MessageQueryDTO;
import com.jun.dto.MessageReadDTO;
import com.jun.dto.SendDTO;
import com.jun.result.Result;

public interface MessageService {
    Result getOrCreatConversation(Long targetUserId);

    Result getConversations();

    Result getConversationDetail(Long conversationId);

    Result getHistory(MessageQueryDTO messageQueryDTO);

    Result send(SendDTO sendDTO);

    Result read(MessageReadDTO messageReadDTO);
}
