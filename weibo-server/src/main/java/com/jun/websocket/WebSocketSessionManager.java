package com.jun.websocket;

import cn.hutool.json.JSONUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jun.vo.NotifyUnreadCountVO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.SessionLimitExceededException;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * WebSocket 连接管理：维护 userId 与在线连接的映射，统一负责连接注册、移除、
 * 查询与消息发送。消息协议（PING/PONG/CHAT/ACK/未读数）仍由 ChatWebSocketHandler 处理。
 */
@Slf4j
@Component
public class WebSocketSessionManager {

    /**
     * 会话属性：当前请求的并发安全发送装饰器。
     * 当前请求的响应始终路由回发起它的连接，不受连接映射后续变化的影响。
     */
    public static final String ATTR_SAFE_SESSION = "safeSession";

    /**
     * 会话属性：最后活跃时间（毫秒时间戳），供空闲扫描清理半开连接
     */
    public static final String ATTR_LAST_ACTIVITY = "lastActivity";

    // userId -> 该用户的活跃连接列表（支持同一用户多标签页同时在线，推送广播到全部连接）
    private final Map<Long, List<WebSocketSession>> userSessions = new ConcurrentHashMap<>();

    @Resource
    private ObjectMapper objectMapper;

    /**
     * 连接空闲超时（秒）：超过该时间未收到任何消息（含 PING）的连接将被服务端关闭
     */
    @Value("${jun.websocket.idle-timeout-seconds:300}")
    private int idleTimeoutSeconds;

    /**
     * 注册连接：包装为并发安全会话并加入在线映射。
     *
     * @return 并发安全装饰器，调用方应使用它发送只属于当前连接的响应
     */
    public WebSocketSession registerSession(Long userId, WebSocketSession session) {
        // 处理 WebSocket 会话在多线程环境下的并发安全问题
        WebSocketSession safeSession =
                new ConcurrentWebSocketSessionDecorator(
                        session, 10_000, 512 * 1024
                );

        // 把包装器引用存入会话属性：当前请求的响应始终路由回发起它的连接，
        // 不受连接映射后续变化的影响，同时复用装饰器的并发发送保护
        session.getAttributes().put(ATTR_SAFE_SESSION, safeSession);

        // 连接活跃时间初值
        session.getAttributes().put(ATTR_LAST_ACTIVITY, System.currentTimeMillis());

        userSessions.computeIfAbsent(userId, k -> new CopyOnWriteArrayList<>())
                .add(safeSession);

        return safeSession;
    }

    /**
     * 移除连接：按 sessionId 精确匹配，只移除当前关闭的这个连接，
     * 同一用户的其他连接（如另一个标签页）不受影响。
     */
    public void removeSession(WebSocketSession session) {
        Long userId = (Long) session.getAttributes().get("userId");
        if (userId == null) {
            return;
        }

        List<WebSocketSession> sessions = userSessions.get(userId);
        if (sessions != null) {
            sessions.removeIf(s -> s.getId().equals(session.getId()));
            if (sessions.isEmpty()) {
                userSessions.remove(userId, sessions);
            }
        }
    }

    /**
     * 获取用户当前所有在线连接的快照；用户不在线时返回空列表。
     */
    public List<WebSocketSession> getSessions(Long userId) {
        List<WebSocketSession> sessions = userSessions.get(userId);
        return sessions == null ? List.of() : List.copyOf(sessions);
    }

    /**
     * 判断用户当前是否有在线连接。
     */
    public boolean isOnline(Long userId) {
        List<WebSocketSession> sessions = userSessions.get(userId);
        return sessions != null && !sessions.isEmpty();
    }

    /**
     * 获取连接对应的并发安全装饰器；未经注册的会话回退为原会话。
     */
    public WebSocketSession getSafeSession(WebSocketSession session) {
        WebSocketSession safeSession =
                (WebSocketSession) session.getAttributes().get(ATTR_SAFE_SESSION);
        return safeSession == null ? session : safeSession;
    }

    /**
     * 刷新连接活跃时间，供空闲扫描清理半开连接。
     */
    public void touch(WebSocketSession session) {
        session.getAttributes().put(ATTR_LAST_ACTIVITY, System.currentTimeMillis());
    }

    /**
     * 统一发送入口：捕获并记录发送异常，不向上抛出，避免单次发送失败
     * 中断消息处理流程或误关发送者连接（异常原先会抛给 ExceptionWebSocketHandlerDecorator 关闭会话）。
     * 仅对 ConcurrentWebSocketSessionDecorator 判定"不可靠"的会话（发送超时/缓冲超限）按官方语义主动关闭，
     * 交由 afterConnectionClosed 清理映射，防止慢客户端每条消息都阻塞到发送超时。
     *
     * @return 是否发送成功（会话不可用或发送失败均返回 false）
     */
    public boolean sendMessageQuietly(WebSocketSession session, TextMessage message, String scene) {
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

    // 根据 userId 查找 Session，适用于 HTTP 请求等场景
    public void sendUnreadCount(Long userId, Long count) {
        List<WebSocketSession> sessions = userSessions.get(userId);
        if (sessions != null) {
            sendUnreadCount(sessions, count);
        }
    }

    // 向指定 Session 列表发送，适用于已经获取 targetSessions 的场景
    public void sendUnreadCount(List<WebSocketSession> sessions, Long count) {
        for (WebSocketSession session : sessions) {
            sendUnreadCount(session, count);
        }
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

    /**
     * 向指定用户的所有在线连接发送同一业务负载（多标签页同时在线时每个连接都收到）。
     * 负载由调用方构造，scene 用于日志定位；用户不在线时静默跳过，
     * 序列化或发送失败仅记录日志，不向调用方抛出。
     */
    public void sendToUser(Long userId, Object payload, String scene) {
        List<WebSocketSession> sessions = userSessions.get(userId);
        if (sessions == null || sessions.isEmpty()) {
            return;
        }

        String json;
        try {
            json = objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            log.warn("构造 WebSocket 推送负载失败，scene = {}，userId = {}", scene, userId, e);
            return;
        }

        for (WebSocketSession session : sessions) {
            if (session.isOpen()) {
                sendMessageQuietly(session, new TextMessage(json), scene);
            }
        }
    }

    /**
     * 向指定用户的所有在线连接推送业务通知。
     * 适用于 MQ 消费者等无 WebSocket 上下文的场景（如转发落库结果、抽奖参与结果），
     * 与 {@link #sendToUser(Long, Object, String)} 共用同一发送与异常处理逻辑。
     */
    public void sendNotification(Long userId, Object payload, String scene) {
        sendToUser(userId, payload, scene);
    }

    /**
     * 空闲连接扫描：清理半开连接（客户端异常断网后服务端收不到关闭帧）。
     * 活跃时间由 ChatWebSocketHandler 每次收到消息（含 PING）时刷新，
     * 正常客户端心跳间隔必须小于 jun.websocket.idle-timeout-seconds。
     */
    @Scheduled(fixedDelay = 60_000)
    public void sweepIdleSessions() {
        long now = System.currentTimeMillis();
        userSessions.forEach((userId, sessions) -> {
            for (WebSocketSession session : sessions) {
                Long lastActivity = (Long) session.getAttributes().get(ATTR_LAST_ACTIVITY);
                if (lastActivity != null
                        && now - lastActivity > idleTimeoutSeconds * 1000L) {
                    log.warn("WebSocket 空闲超时，关闭连接，userId = {}，sessionId = {}",
                            userId, session.getId());
                    try {
                        session.close(CloseStatus.GOING_AWAY);
                    } catch (IOException e) {
                        log.warn("关闭空闲连接失败，sessionId = {}", session.getId());
                    }
                }
            }
        });
    }
}
