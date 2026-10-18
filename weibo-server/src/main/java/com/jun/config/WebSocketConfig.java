package com.jun.config;

import com.jun.interceptor.JwtHandshakeInterceptor;
import com.jun.websocket.ChatWebSocketHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final ChatWebSocketHandler chatWebSocketHandler;
    private final JwtHandshakeInterceptor jwtHandshakeInterceptor;

    /**
     * WebSocket 握手 Origin 白名单，逗号分隔；
     * 由 jun.websocket.allowed-origins 配置，生产环境覆盖为真实前端域名
     */
    private final String[] allowedOrigins;

    public WebSocketConfig(ChatWebSocketHandler chatWebSocketHandler,
                           JwtHandshakeInterceptor jwtHandshakeInterceptor,
                           @Value("${jun.websocket.allowed-origins}") String[] allowedOrigins) {
        this.chatWebSocketHandler = chatWebSocketHandler;
        this.jwtHandshakeInterceptor = jwtHandshakeInterceptor;
        this.allowedOrigins = allowedOrigins;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // WebSocket 连接地址交给 ChatWebSocketHandler 处理
        registry.addHandler(
                chatWebSocketHandler,
                "/ws/chat"
        )
        .addInterceptors(jwtHandshakeInterceptor)
        .setAllowedOrigins(allowedOrigins);
    }
}