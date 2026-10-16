package com.jun.interceptor;

import com.jun.properties.JwtProperties;
import com.jun.utils.JwtUtil;
import io.jsonwebtoken.Claims;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.net.URI;
import java.util.Map;

@Slf4j
@Component
public class JwtHandshakeInterceptor
        implements HandshakeInterceptor {

    @Autowired
    private JwtProperties jwtProperties;

    // WebSocket 建立之前
    @Override
    public boolean beforeHandshake(
            ServerHttpRequest request,
            ServerHttpResponse response,
            WebSocketHandler wsHandler,
            Map<String, Object> attributes) {

        log.info("WebSocket开始握手");
        String token = getToken(request);

        if (token == null) {
            return false;
        }

        try {
            Claims claims = JwtUtil.parseJWT(jwtProperties.getUserSecretKey(),token);
            Long userId = claims.get("userId", Long.class);

            attributes.put("userId", userId);

            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public void afterHandshake(
            ServerHttpRequest request,
            ServerHttpResponse response,
            WebSocketHandler wsHandler,
            Exception exception) {

    }

    private String getToken(ServerHttpRequest request) {

        URI uri = request.getURI();
        String query = uri.getQuery();

        if (query == null) {
            return null;
        }

        for (String param : query.split("&")) {
            String[] pair = param.split("=", 2);

            if (pair.length == 2
                    && pair[0].equals("token")) {
                return pair[1];
            }
        }

        return null;
    }
}