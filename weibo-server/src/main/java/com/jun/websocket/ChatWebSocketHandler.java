package com.jun.websocket;

import cn.hutool.json.JSONUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jun.dto.ChatMessageDTO;
import com.jun.vo.ChatMessageVO;
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

            // 2.2 消息类型校验
            String type = chatMessage.getType();
            if (!"PING".equals(type) && !"CHAT".equals(type)) {
                log.warn("WebSocket 收到不支持的消息类型，userId={}", session.getAttributes().get("userId"));

                senderSession.sendMessage(new TextMessage("{\"type\":\"ERROR\",\"message\":\"不支持的消息类型\"}"));
                return;
            }

            // 2.1 捕获 JSON 解析异常
        } catch (JsonProcessingException e) {
            log.warn("WebSocket 收到非法 JSON，userId={}",session.getAttributes().get("userId"));
            senderSession.sendMessage(new TextMessage("{\"type\":\"ERROR\",\"message\":\"消息格式错误\"}"));
            return;
        }

        // 业务层心跳机制
        if ("PING".equals(chatMessage.getType())) {
            senderSession.sendMessage(new TextMessage("{\"type\":\"PONG\"}"));
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

        // 3. 保存消息到 MySQL
        ChatMessageVO messageVO;
        try {
            messageVO = messageService.saveMessage(
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

        // 返回给客户端的确认消息入库
        ChatMessageVO ack = new ChatMessageVO();
        ack.setType("MESSAGE_ACK");
        ack.setClientMessageId(chatMessage.getClientMessageId());
        ack.setMessageId(messageVO.getMessageId());
        String ackJson = JSONUtil.toJsonStr(ack);
        senderSession.sendMessage(new TextMessage(ackJson));

        // 4. 根据服务器计算出的 receiverId 找接收者
        WebSocketSession targetSession =
                safeOnlineUsers.get(messageVO.getReceiverId());

        // 5. 接收者在线
        if (targetSession != null
                && targetSession.isOpen()) {

            // 6. 把完整消息对象转换成 JSON
            String json = objectMapper.writeValueAsString(messageVO);

            // 7. 推送给接收者
            targetSession.sendMessage(new TextMessage(json));

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

        try {
            session.sendMessage(new TextMessage(json));
        } catch (IOException e) {
            log.warn("发送未读数通知失败", e);
        }
    }

    // 修改：统一构造 ERROR 响应；clientMessageId 非空时携带，供前端精确匹配失败的发送消息；
    // 未携带（如非法 JSON、不支持的消息类型等连接级/协议级错误）时前端只提示，不影响任何消息状态
    private void sendError(WebSocketSession session, String message, String clientMessageId) {
        try {
            Map<String, String> error = new HashMap<>();
            error.put("type", "ERROR");
            if (StringUtils.hasText(clientMessageId)) {
                error.put("clientMessageId", clientMessageId);
            }
            error.put("message", message);
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(error)));
        } catch (IOException e) {
            log.warn("发送 ERROR 响应失败", e);
        }
    }
}

