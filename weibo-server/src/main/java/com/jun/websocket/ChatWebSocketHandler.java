package com.jun.websocket;

import cn.hutool.json.JSONUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jun.dto.ChatMessageDTO;
import com.jun.vo.ChatMessageVO;
import com.jun.vo.MessageSaveResult;
import com.jun.service.MessageService;
import com.jun.vo.NotifyUnreadCountVO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.SessionLimitExceededException;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class ChatWebSocketHandler extends TextWebSocketHandler {

    @Resource
    private ObjectMapper objectMapper;
    @Resource
    private MessageService messageService;

    // userId -> WebSocketSession
    private final Map<Long, WebSocketSession> safeOnlineUsers = new ConcurrentHashMap<>();

    // 客户端连接成功时执行
    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        Long userId = (Long) session.getAttributes().get("userId");

        // 保存用户和 WebSocket 连接的对应关系
        // 处理 WebSocket 会话在多线程环境下的并发安全问题
        WebSocketSession safeSession =
                new ConcurrentWebSocketSessionDecorator(
                        session, 10_000, 512 * 1024
                );

        safeOnlineUsers.put(userId, safeSession);

        log.info("用户 {} WebSocket连接成功",userId);
        log.info("sessionId = {}",safeSession.getId());

        // 未读消息数量推送
        Long userUnReadCount = messageService.getUserUnReadCount(userId);
        sendUnreadCount(safeSession,userUnReadCount);
    }

    // 客户端发送文本消息时执行
    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws IOException {
        // 1. 获取发送者
        Long senderId = (Long) session.getAttributes().get("userId");

        WebSocketSession senderSession = safeOnlineUsers.get(senderId);

        // 2. 解析消息
        ChatMessageDTO chatMessage;
        try {
            chatMessage = objectMapper.readValue(
                    message.getPayload(),
                    ChatMessageDTO.class
            );

            // JSON 字面量 "null" 反序列化结果为 null 而不是抛异常，按协议级错误处理
            if (chatMessage == null) {
                log.warn("WebSocket 收到 JSON null，userId={}", session.getAttributes().get("userId"));
                sendError(senderSession, "消息格式错误", null);
                return;
            }

            // 2.2 消息类型校验
            String type = chatMessage.getType();
            if (!"PING".equals(type) && !"CHAT".equals(type)) {
                log.warn("WebSocket 收到不支持的消息类型，userId={}", session.getAttributes().get("userId"));

                sendError(senderSession, "不支持的消息类型", null);
                return;
            }

            // 2.1 捕获 JSON 解析异常
        } catch (JsonProcessingException e) {
            log.warn("WebSocket 收到非法 JSON，userId={}",session.getAttributes().get("userId"));
            sendError(senderSession, "消息格式错误", null);
            return;
        }

        // 业务层心跳机制
        if ("PING".equals(chatMessage.getType())) {
            sendMessageQuietly(senderSession, new TextMessage("{\"type\":\"PONG\"}"), "PONG");
            return;
        }

        // 仅校验 CHAT 消息的业务参数
        if (chatMessage.getConversationId() == null) {
            sendError(senderSession, "会话 ID 不能为空", chatMessage.getClientMessageId());
            return;
        }

        String content = chatMessage.getContent();
        if (!StringUtils.hasText(content) || content.length() > 1000) {
            sendError(senderSession, "消息内容不合法", chatMessage.getClientMessageId());
            return;
        }

        // 3. 保存消息到 MySQL（幂等：重复 clientMessageId 返回原消息并标记 replay）
        MessageSaveResult saveResult;
        try {
            saveResult = messageService.saveMessage(
                    senderId,
                    chatMessage.getConversationId(),
                    chatMessage.getContent(),
                    chatMessage.getClientMessageId()
            );
        } catch (RuntimeException e) {
            log.warn("WebSocket 消息处理失败，userId={}, reason={}", senderId, e.getMessage());
            sendError(senderSession, "消息发送失败", chatMessage.getClientMessageId());
            return;
        }

        ChatMessageVO messageVO = saveResult.getMessage();

        // 返回给客户端的确认消息入库（首次创建与幂等重放都返回指向原 messageId 的 ACK）。
        // ACK 发送失败不代表数据库保存失败：消息已持久化，客户端可复用原 clientMessageId 重试获取幂等 ACK
        ChatMessageVO ack = new ChatMessageVO();
        ack.setType("MESSAGE_ACK");
        ack.setClientMessageId(chatMessage.getClientMessageId());
        ack.setMessageId(messageVO.getMessageId());
        String ackJson = JSONUtil.toJsonStr(ack);
        sendMessageQuietly(senderSession, new TextMessage(ackJson), "MESSAGE_ACK");

        // 幂等重放：消息此前已保存并推送过，不得再次向接收者推送 NEW_MESSAGE 和未读数
        if (saveResult.isReplay()) {
            log.info("幂等重放，跳过重复推送，senderId = {}, messageId = {}",
                    senderId, messageVO.getMessageId());
            return;
        }

        // 4. 根据服务器计算出的 receiverId 找接收者
        WebSocketSession targetSession =
                safeOnlineUsers.get(messageVO.getReceiverId());

        // 5. 接收者在线
        if (targetSession != null
                && targetSession.isOpen()) {

            // 6. 把完整消息对象转换成 JSON
            String json = objectMapper.writeValueAsString(messageVO);

            // 7. 推送给接收者；推送失败不影响已持久化的消息和已增加的未读数，
            //    也不应中断发送者的处理流程（此前异常上抛会误关发送者连接）
            sendMessageQuietly(targetSession, new TextMessage(json), "NEW_MESSAGE");

            // 未读消息数量推送
            Long userUnReadCount = messageService.getUserUnReadCount(messageVO.getReceiverId());
            sendUnreadCount(targetSession,userUnReadCount);

            // 日志脱敏：不输出私信正文，仅保留 ID 与内容长度用于排查
            log.info(
                    "用户 {} → 用户 {}：消息已推送，conversationId = {}，messageId = {}，contentLength = {}",
                    messageVO.getSenderId(),
                    messageVO.getReceiverId(),
                    messageVO.getConversationId(),
                    messageVO.getMessageId(),
                    messageVO.getContent() == null ? 0 : messageVO.getContent().length()
            );

        } else {

            log.info(
                    "用户 {} 当前不在线，消息已保存，messageId = {}",
                    messageVO.getReceiverId(),
                    messageVO.getMessageId()
            );
        }
    }

    // 客户端断开时执行
    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Long userId = (Long) session.getAttributes().get("userId");

        // 只有关闭的连接仍是当前登记的连接时，才删除映射
        safeOnlineUsers.computeIfPresent(userId, (id, onlineSession) ->
                onlineSession.getId().equals(session.getId())
                        ? null              // 关闭的就是登记中的连接
                        : onlineSession     // 关闭的是旧连接，保留有效连接
        );

        log.info("WebSocket连接关闭，用户：{}，sessionId = {}",userId,session.getId());
    }

    // 根据 userId 查找 Session，适用于 HTTP 请求等场景
    public void sendUnreadCount(Long userId, Long count) {
        WebSocketSession session = safeOnlineUsers.get(userId);
        sendUnreadCount(session, count);
    }

    // 向指定 Session 发送，适用于已经获取 targetSession 的场景
    public void sendUnreadCount(WebSocketSession session, Long count) {
        if (session == null || !session.isOpen()) {
            return;
        }

        NotifyUnreadCountVO unreadCount = NotifyUnreadCountVO.builder()
                .count(count)
                .type("UNREAD_COUNT")
                .build();

        String json = JSONUtil.toJsonStr(unreadCount);

        sendMessageQuietly(session, new TextMessage(json), "UNREAD_COUNT");
    }

    // 修改：统一构造 ERROR 响应；clientMessageId 非空时携带，供前端精确匹配失败的发送消息；
    // 未携带（如非法 JSON、不支持的消息类型等连接级/协议级错误）时前端只提示，不影响任何消息状态。
    // 单次发送、失败仅记录日志，不重试，避免连接已不可用时反复发送 ERROR
    private void sendError(WebSocketSession session, String message, String clientMessageId) {
        try {
            Map<String, String> error = new HashMap<>();
            error.put("type", "ERROR");
            if (StringUtils.hasText(clientMessageId)) {
                error.put("clientMessageId", clientMessageId);
            }
            error.put("message", message);
            sendMessageQuietly(session, new TextMessage(objectMapper.writeValueAsString(error)), "ERROR");
        } catch (JsonProcessingException e) {
            log.warn("构造 ERROR 响应失败", e);
        }
    }

    /**
     * 统一发送入口：捕获并记录发送异常，不向上抛出，避免单次发送失败
     * 中断消息处理流程或误关发送者连接（异常原先会抛给 ExceptionWebSocketHandlerDecorator 关闭会话）。
     * 仅对 ConcurrentWebSocketSessionDecorator 判定"不可靠"的会话（发送超时/缓冲超限）按官方语义主动关闭，
     * 交由 afterConnectionClosed 清理映射，防止慢客户端每条消息都阻塞到发送超时。
     *
     * @return 是否发送成功（会话不可用或发送失败均返回 false）
     */
    private boolean sendMessageQuietly(WebSocketSession session, TextMessage message, String scene) {
        if (session == null || !session.isOpen()) {
            log.warn("WebSocket 发送跳过（会话不可用），scene = {}，sessionId = {}",
                    scene, session == null ? "null" : session.getId());
            return false;
        }

        try {
            session.sendMessage(message);
            return true;
        } catch (SessionLimitExceededException e) {
            // 发送超时（10s）或缓冲超限（512KB）：会话已不可靠，主动关闭，避免无限占用
            log.warn("WebSocket 会话不可靠，关闭连接，scene = {}，sessionId = {}，reason = {}",
                    scene, session.getId(), e.getMessage());
            try {
                session.close(e.getStatus());
            } catch (IOException closeEx) {
                log.warn("关闭不可靠会话失败，sessionId = {}", session.getId());
            }
            return false;
        } catch (IOException e) {
            // 底层连接已断或网络异常：记录后放弃，连接清理交给容器回调
            log.warn("WebSocket 发送失败，scene = {}，sessionId = {}，error = {}",
                    scene, session.getId(), e.getMessage());
            return false;
        } catch (RuntimeException e) {
            // 会话正在关闭/已关闭（IllegalStateException）等其他运行时异常，同样仅记录
            log.warn("WebSocket 发送失败，scene = {}，sessionId = {}，error = {}",
                    scene, session.getId(), e.getMessage());
            return false;
        }
    }
}

