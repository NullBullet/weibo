package com.jun.service.impl;

import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import com.jun.context.BaseContext;
import com.jun.dto.MessageQueryDTO;
import com.jun.dto.MessageReadDTO;
import com.jun.dto.SendDTO;
import com.jun.entity.Conversation;
import com.jun.entity.ConversationUser;
import com.jun.entity.PrivateMessage;
import com.jun.entity.User;
import com.jun.mapper.MessageMapper;
import com.jun.mapper.UsersMapper;
import com.jun.result.Result;
import com.jun.service.MessageService;
import com.jun.vo.ConversationDetailVO;
import com.jun.vo.ConversationVO;
import com.jun.vo.MessageVO;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class MessageServiceImpl implements MessageService {

    @Resource
    private MessageMapper messageMapper;
    @Resource
    private UsersMapper usersMapper;

    @Override
    public Result getOrCreatConversation(Long targetUserId) {
        Long currentUserId = BaseContext.getCurrentId();

        // 1. 用户 ID 排序
        Long user1 = Math.min(currentUserId, targetUserId);
        Long user2 = Math.max(currentUserId, targetUserId);

        // 2. 查找或创建会话
        Long conversationId = messageMapper.getConversation(user1, user2);
        if (conversationId == null) {
            LocalDateTime now = LocalDateTime.now();
            Conversation conversation = new Conversation();
            conversation.setUser1Id(user1);
            conversation.setUser2Id(user2);
            conversation.setCreateTime(now);
            messageMapper.insertConversation(conversation);

            Long newId = conversation.getId();
            ConversationUser cu1 = new ConversationUser();
            cu1.setConversationId(newId);
            cu1.setUserId(user1);
            cu1.setCreateTime(now);
            messageMapper.insertConversationUser(cu1);

            ConversationUser cu2 = new ConversationUser();
            cu2.setConversationId(newId);
            cu2.setUserId(user2);
            cu2.setCreateTime(now);
            messageMapper.insertConversationUser(cu2);

            conversationId = newId;
        }

        Map<String, Object> result = new HashMap<>();
        result.put("conversationId", conversationId);
        return Result.success(result);
    }

    @Override
    public Result getConversations() {
        Long userId = BaseContext.getCurrentId();
        List<ConversationVO> list = messageMapper.selectConversationList(userId);
        return Result.success(list);
    }

    @Override
    public Result getConversationDetail(Long conversationId) {
        Long userId = BaseContext.getCurrentId();
        Conversation conv = messageMapper.selectConversationById(conversationId);
        if (conv == null) {
            return Result.error("会话不存在");
        }

        // 确定对方用户 ID
        Long otherUserId = conv.getUser1Id().equals(userId) ? conv.getUser2Id() : conv.getUser1Id();
        User otherUser = usersMapper.getUserInfoByUserId(otherUserId);

        ConversationDetailVO.TargetUser targetUser = new ConversationDetailVO.TargetUser();
        targetUser.setId(otherUser.getId());
        targetUser.setNickname(otherUser.getNickname());
        targetUser.setAvatar(otherUser.getAvatar());

        ConversationDetailVO vo = new ConversationDetailVO();
        vo.setConversationId(conversationId);
        vo.setTargetUser(targetUser);

        return Result.success(vo);
    }

    @Override
    public Result getHistory(MessageQueryDTO dto) {
        Integer page = dto.getPage() != null ? dto.getPage() : 1;
        Integer pageSize = dto.getPageSize() != null ? dto.getPageSize() : 20;

        PageHelper.startPage(page, pageSize);
        Page<MessageVO> pageResult = messageMapper.selectMessagesByPage(dto.getConversationId());
        List<MessageVO> records = pageResult.getResult();

        return Result.success(records);
    }

    @Override
    public Result send(SendDTO sendDTO) {
        Long senderId = BaseContext.getCurrentId();
        Long conversationId = sendDTO.getConversationId();
        String content = sendDTO.getContent();

        // 1. 获取会话确定接收方
        Conversation conv = messageMapper.selectConversationById(conversationId);
        if (conv == null) {
            return Result.error("会话不存在");
        }
        Long receiverId = conv.getUser1Id().equals(senderId) ? conv.getUser2Id() : conv.getUser1Id();

        // 2. 插入消息
        LocalDateTime now = LocalDateTime.now();
        PrivateMessage message = new PrivateMessage();
        message.setConversationId(conversationId);
        message.setSenderId(senderId);
        message.setReceiverId(receiverId);
        message.setContent(content);
        message.setCreateTime(now);
        messageMapper.insertMessage(message);
        Long messageId = message.getId();

        // 3. 更新会话最后消息（varchar(500) 截断）
        String lastMsg = content.length() > 500 ? content.substring(0, 500) : content;
        messageMapper.updateConversationLastMessage(conversationId, lastMsg, now);

        // 4. 增加对方未读数
        messageMapper.incrementUnreadCount(conversationId, receiverId);

        Map<String, Object> result = new HashMap<>();
        result.put("messageId", messageId);
        return Result.success(result);
    }

    @Override
    public Result read(MessageReadDTO messageReadDTO) {
        Long userId = BaseContext.getCurrentId();

        Long conversationId = messageReadDTO.getConversationId();
        // 1. 标记消息已读
        messageMapper.updateMessagesRead(conversationId, userId);

        // 2. 获取最后一条消息 ID
        Long latestMsgId = messageMapper.selectLatestMessageId(conversationId);

        // 3. 清空未读数
        messageMapper.resetUnreadCount(conversationId, userId, latestMsgId);

        return Result.success();
    }
}
