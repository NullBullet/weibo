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

    /** 历史消息单页条数上限，防止超大 pageSize 一次拉取过多数据 */
    private static final int MAX_PAGE_SIZE = 100;

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

        // 越权防护：仅会话成员可查看会话详情
        Conversation conv = getConversationForCurrentUser(conversationId, userId);

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
        Long currentUserId = BaseContext.getCurrentId();

        // 越权防护：仅会话成员可读取历史消息
        getConversationForCurrentUser(dto.getConversationId(), currentUserId);

        // 服务端归一化分页参数：非法值回退默认值，并强制单页上限
        int page = dto.getPage() != null && dto.getPage() > 0 ? dto.getPage() : 1;
        int pageSize = dto.getPageSize() != null && dto.getPageSize() > 0 ? dto.getPageSize() : 20;
        pageSize = Math.min(pageSize, MAX_PAGE_SIZE);

        PageHelper.startPage(page, pageSize);
        Page<MessageVO> pageResult = messageMapper.selectMessagesByPage(dto.getConversationId());
        List<MessageVO> records = pageResult.getResult();

        return Result.success(records);
    }

    @Transactional
    @Override
    public MessageSaveResult saveMessage(
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

            // 幂等重放：返回已有消息，不重复插入，不重复增加未读数，调用方也不重复推送
            return MessageSaveResult.replayed(convertToChatMessageVO(existing));
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
            // 冲突行必已提交（若未提交会先在 insert 上等锁），
            // 用 FOR UPDATE 当前读补偿查询，避免 REPEATABLE READ 快照看不到并发已提交记录导致虚假失败
            existing = messageMapper.selectBySenderAndClientMessageIdForUpdate(
                            senderId, clientMessageId);

            if (existing == null) {
                throw e;
            }

            if (!Objects.equals(existing.getConversationId(), conversationId)
                    || !Objects.equals(existing.getContent(), content)) {
                throw new IllegalArgumentException(
                        "客户端消息ID已被其他消息使用");
            }

            // 幂等重放：并发冲突时命中的是其他事务刚提交的原消息
            return MessageSaveResult.replayed(convertToChatMessageVO(existing));
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

        // 增量 +1（条件：新消息位于已读位置之后），与 read 的递减操作可交换，并发下不丢计数
        messageMapper.incrementUnreadCount(
                conversationId,
                receiverId,
                messageId
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

        // 首次创建
        return MessageSaveResult.created(vo);
    }

    @Override
    public Long getUserUnReadCount(Long UserId) {
        List<Long> unReadList = messageMapper.getUserUnReadCount(UserId);
        return unReadList.stream().mapToLong(Long::longValue).sum();
    }

    @Transactional
    @Override
    public Result read(MessageReadDTO messageReadDTO) {
        Long userId = BaseContext.getCurrentId();

        Long conversationId = messageReadDTO.getConversationId();

        // 1. 以当前时刻的最大消息 ID 作为本次已读基准点
        Long latestMsgId = messageMapper.selectLatestMessageId(conversationId);

        // 会话内暂无消息，无需标记
        if (latestMsgId == null) {
            return Result.success();
        }

        // 2. 标记基准点之前的消息已读，并记录本次标记条数
        int markedCount = messageMapper.updateMessagesRead(conversationId, userId, latestMsgId);

        // 3. 原子递减未读数（而非无条件清零）：减去本次标记的条数；
        //    last_read_message_id 单调推进，重复/并发 read 不会二次递减，
        //    与 saveMessage 的条件化 +1 可交换，新消息并发到达时计数不丢失
        messageMapper.decreaseUnreadCount(conversationId, userId, markedCount, latestMsgId);

        // 未读消息数量推送
        Long userUnReadCount = messageService.getUserUnReadCount(userId);
        chatWebSocketHandler.sendUnreadCount(userId,userUnReadCount);

        return Result.success();
    }

    /**
     * 查询会话并校验当前用户是否为会话成员（user1Id 或 user2Id）。
     * 会话不存在与无权访问统一按"会话不存在"处理，避免向非成员泄露他人会话的存在性。
     */
    private Conversation getConversationForCurrentUser(Long conversationId, Long currentUserId) {
        Conversation conv = messageMapper.selectConversationById(conversationId);
        if (conv == null
                || (!conv.getUser1Id().equals(currentUserId)
                    && !conv.getUser2Id().equals(currentUserId))) {
            throw new SessionNotFoundException("会话不存在");
        }
        return conv;
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
