package com.jun.service.impl;

import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import com.jun.context.BaseContext;
import com.jun.vo.*;
import com.jun.dto.MessageQueryDTO;
import com.jun.dto.MessageReadDTO;
import com.jun.entity.Conversation;
import com.jun.entity.ConversationUser;
import com.jun.entity.PrivateMessage;
import com.jun.entity.User;
import com.jun.exception.MessageSendPermissionDeniedException;
import com.jun.exception.SessionNotFoundException;
import com.jun.mapper.MessageMapper;
import com.jun.mapper.UsersMapper;
import com.jun.result.Result;
import com.jun.service.MessageService;
import com.jun.websocket.ChatWebSocketHandler;
import jakarta.annotation.Resource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class MessageServiceImpl implements MessageService {

    @Resource
    private MessageMapper messageMapper;
    @Resource
    private UsersMapper usersMapper;
    @Resource
    private MessageService messageService;
    @Resource
    private ChatWebSocketHandler chatWebSocketHandler;

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

    @Transactional
    @Override
    public ChatMessageVO saveMessage(
            Long senderId,
            Long conversationId,
            String content,
            String clientMessageId) {

        // 1. 校验客户端消息 ID
        if (!StringUtils.hasText(clientMessageId)
                || clientMessageId.length() > 64) {
            throw new IllegalArgumentException("客户端消息ID无效");
        }

        // 2. 查询是否已经处理过这条消息
        PrivateMessage existing =
                messageMapper.selectBySenderAndClientMessageId(
                        senderId, clientMessageId);

        if (existing != null) {
            // 同一个 ID 不能被用于发送另一条消息
            if (!Objects.equals(existing.getConversationId(), conversationId)
                    || !Objects.equals(existing.getContent(), content)) {
                throw new IllegalArgumentException("客户端消息ID已被其他消息使用");
            }

            // 返回已有消息，不重复插入，也不重复增加未读数
            return convertToChatMessageVO(existing);
        }

        Conversation conv =
                messageMapper.selectConversationById(conversationId);

        if (conv == null) {
            throw new SessionNotFoundException("会话不存在");
        }

        if (!conv.getUser1Id().equals(senderId)
                && !conv.getUser2Id().equals(senderId)) {
            throw new MessageSendPermissionDeniedException("无权在该会话中发送消息");
        }

        Long receiverId =
                conv.getUser1Id().equals(senderId)
                        ? conv.getUser2Id()
                        : conv.getUser1Id();

        LocalDateTime now = LocalDateTime.now();

        PrivateMessage message = new PrivateMessage();
        message.setConversationId(conversationId);
        message.setSenderId(senderId);
        message.setClientMessageId(clientMessageId);
        message.setReceiverId(receiverId);
        message.setContent(content);
        message.setCreateTime(now);

        try {
            messageMapper.insertMessage(message);
        } catch (DuplicateKeyException e) {
            existing = messageMapper.selectBySenderAndClientMessageId(
                            senderId, clientMessageId);

            if (existing == null) {
                throw e;
            }

            if (!Objects.equals(existing.getConversationId(), conversationId)
                    || !Objects.equals(existing.getContent(), content)) {
                throw new IllegalArgumentException(
                        "客户端消息ID已被其他消息使用");
            }

            return convertToChatMessageVO(existing);
        }

        Long messageId = message.getId();

        String lastMsg =
                content.length() > 500
                        ? content.substring(0, 500)
                        : content;

        messageMapper.updateConversationLastMessage(
                conversationId,
                lastMsg,
                now
        );

        messageMapper.incrementUnreadCount(
                conversationId,
                receiverId
        );

        ChatMessageVO vo = new ChatMessageVO();
        vo.setType("NEW_MESSAGE");
        vo.setMessageId(messageId);
        vo.setClientMessageId(clientMessageId);
        vo.setConversationId(conversationId);
        vo.setSenderId(senderId);
        vo.setReceiverId(receiverId);
        vo.setContent(content);
        vo.setCreateTime(now);

        return vo;
    }

    @Override
    public Long getUserUnReadCount(Long UserId) {
        List<Long> unReadList = messageMapper.getUserUnReadCount(UserId);
        return unReadList.stream().mapToLong(Long::longValue).sum();
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

        // 未读消息数量推送
        Long userUnReadCount = messageService.getUserUnReadCount(userId);
        chatWebSocketHandler.sendUnreadCount(userId,userUnReadCount);

        return Result.success();
    }

    /**
     * 将私信实体转换为 WebSocket 消息 VO
     */
    private ChatMessageVO convertToChatMessageVO(PrivateMessage message) {
        ChatMessageVO vo = new ChatMessageVO();

        vo.setType("NEW_MESSAGE");
        vo.setMessageId(message.getId());
        vo.setClientMessageId(message.getClientMessageId());
        vo.setConversationId(message.getConversationId());
        vo.setSenderId(message.getSenderId());
        vo.setReceiverId(message.getReceiverId());
        vo.setContent(message.getContent());
        vo.setCreateTime(message.getCreateTime());

        return vo;
    }
}
