package com.jun.websocket;

import cn.hutool.json.JSONUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jun.dto.ChatMessageDTO;
import com.jun.service.MessageService;
import com.jun.service.NotificationsService;
import com.jun.vo.ChatMessageVO;
import com.jun.vo.MessageSaveResult;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * WebSocket 协议处理：连接生命周期、客户端消息接收、PING/PONG 心跳、
 * CHAT 消息校验与落库、ACK 响应，以及把已保存的私信事件分发给 MessageService。
 * 在线连接管理与消息发送统一委托给 {@link WebSocketSessionManager}。
 */
@Slf4j
@Component
public class MessageWebSocketHandler extends TextWebSocketHandler {

    @Resource
    private ObjectMapper objectMapper;
    @Resource
    private MessageService messageService;
    @Resource
    private NotificationsService notificationsService;
    @Resource
    private WebSocketSessionManager sessionManager;
    /**
     * CHAT 消息发送频率限制：每秒补充令牌数，<=0 表示关闭限流
     */
    @Value("${jun.websocket.chat-rate-limit.rate:10}")
    private int chatRateLimit;

    /**
     * CHAT 消息发送频率限制：令牌桶容量（允许的瞬时突发量）
     */
    @Value("${jun.websocket.chat-rate-limit.burst:20}")
    private int chatRateBurst;

    // 客户端连接成功时执行
    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        Long userId = (Long) session.getAttributes().get("userId");

        // 保存用户和 WebSocket 连接的对应关系（内部包装并发安全会话）
        WebSocketSession safeSession = sessionManager.registerSession(userId, session);

        // CHAT 频率限流器（连接级）
        if (chatRateLimit > 0) {
            session.getAttributes().put("chatRateLimiter", new ChatRateLimiter(chatRateLimit, chatRateBurst));
        }

        log.info("用户 {} WebSocket连接成功", userId);
        log.info("sessionId = {}", safeSession.getId());

        // 未读消息数量推送：私信 + 评论 + 点赞 + 系统通知
        Long totalUnreadCount = notificationsService.getTotalUnreadCount(userId);
        sessionManager.sendUnreadCount(safeSession, totalUnreadCount);
    }

    // 客户端发送文本消息时执行
    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws IOException {
        // 1. 获取发送者
        Long senderId = (Long) session.getAttributes().get("userId");

        // Token 过期检查：连接存活期间 token 过期后不再处理后续消息，
        // 关闭连接后前端重连会走握手 401，与 HTTP 过期行为一致
        Long tokenExp = (Long) session.getAttributes().get("tokenExp");
        if (tokenExp != null && System.currentTimeMillis() > tokenExp) {
            log.warn("WebSocket token 已过期，关闭连接，userId = {}", senderId);
            sessionManager.sendMessageQuietly(session, new TextMessage("{\"type\":\"ERROR\",\"message\":\"登录已过期\"}"), "ERROR");
            try {
                session.close(CloseStatus.GOING_AWAY);
            } catch (IOException e) {
                log.warn("关闭过期连接失败，userId = {}", senderId);
            }
            return;
        }

        // 刷新连接活跃时间（含 PING），供空闲扫描清理半开连接
        sessionManager.touch(session);

        // 响应路由：使用发起当前请求的连接自己的包装器（PONG/ERROR/ACK 只回到该连接），
        // 不从映射中按 userId 查找，避免同用户多连接时响应发到别的连接；
        // 装饰器在连接建立时已存入会话属性，发送仍受并发保护
        WebSocketSession senderSession = sessionManager.getSafeSession(session);

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
            log.warn("WebSocket 收到非法 JSON，userId={}", session.getAttributes().get("userId"));
            sendError(senderSession, "消息格式错误", null);
            return;
        }

        // 业务层心跳机制
        if ("PING".equals(chatMessage.getType())) {
            sessionManager.sendMessageQuietly(senderSession, new TextMessage("{\"type\":\"PONG\"}"), "PONG");
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

        // 连接级发送频率限制（令牌桶）：超限按发送失败处理，带 clientMessageId 供前端标记失败；
        // 该消息未入库，客户端可稍后复用原 clientMessageId 重试（幂等安全）
        if (chatRateLimit > 0) {
            ChatRateLimiter limiter =
                    (ChatRateLimiter) session.getAttributes().get("chatRateLimiter");
            if (limiter != null && !limiter.tryAcquire()) {
                log.warn("WebSocket 消息发送过于频繁，userId = {}", senderId);
                sendError(senderSession, "发送过于频繁，请稍后再试", chatMessage.getClientMessageId());
                return;
            }
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
        sessionManager.sendMessageQuietly(senderSession, new TextMessage(ackJson), "MESSAGE_ACK");

        // 幂等重放：消息此前已保存并推送过，不得再次向接收者推送 NEW_MESSAGE 和未读数
        if (saveResult.isReplay()) {
            log.info("幂等重放，跳过重复推送，senderId = {}, messageId = {}",
                    senderId, messageVO.getMessageId());
            return;
        }

        // 4. 消息事件分发：私信推送（NEW_MESSAGE + 未读数）由业务层负责，
        //    推送异常不影响已持久化消息和已发送的 ACK
        messageService.pushMessage(messageVO);
    }

    // 客户端断开时执行
    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Long userId = (Long) session.getAttributes().get("userId");

        // 只移除当前关闭的这个连接，同一用户的其他连接（如另一个标签页）不受影响
        sessionManager.removeSession(session);

        log.info("WebSocket连接关闭，用户：{}，sessionId = {}", userId, session.getId());
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
            sessionManager.sendMessageQuietly(session, new TextMessage(objectMapper.writeValueAsString(error)), "ERROR");
        } catch (JsonProcessingException e) {
            log.warn("构造 ERROR 响应失败", e);
        }
    }

    /**
     * 连接级令牌桶限流器：每秒补充 rate 个令牌，桶容量 burst（允许瞬时突发）。
     * 仅在单连接内使用，synchronized 成本可忽略。
     */
    private static final class ChatRateLimiter {
        private final double rate;
        private final double burst;
        private double tokens;
        private long lastRefillNanos;

        ChatRateLimiter(int rate, int burst) {
            this.rate = rate;
            this.burst = burst;
            this.tokens = burst;
            this.lastRefillNanos = System.nanoTime();
        }

        synchronized boolean tryAcquire() {
            long now = System.nanoTime();
            tokens = Math.min(burst, tokens + (now - lastRefillNanos) / 1e9 * rate);
            lastRefillNanos = now;
            if (tokens >= 1) {
                tokens -= 1;
                return true;
            }
            return false;
        }
    }
}
