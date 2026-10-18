package com.jun.interceptor;

import com.jun.constant.JwtClaimsConstant;
import com.jun.properties.JwtProperties;
import com.jun.utils.JwtUtil;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
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

        // 无 Token：拒绝握手，返回 401
        if (!StringUtils.hasText(token)) {
            log.warn("WebSocket 握手拒绝：缺少 token，uri={}", request.getURI());
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }

        try {
            Claims claims = JwtUtil.parseJWT(jwtProperties.getUserSecretKey(), token);
            Long userId = claims.get(JwtClaimsConstant.USER_ID, Long.class);

            // userId 必须存在且合法，不能将 null 放入 WebSocket 会话属性
            if (userId == null || userId <= 0) {
                log.warn("WebSocket 握手拒绝：token 中缺少合法的 userId");
                response.setStatusCode(HttpStatus.UNAUTHORIZED);
                return false;
            }

            attributes.put("userId", userId);

            // 记录 token 过期时间，供连接建立后的过期检查（过期后关闭连接，与 HTTP 401 行为一致）
            java.util.Date expiration = claims.getExpiration();
            if (expiration != null) {
                attributes.put("tokenExp", expiration.getTime());
            }

            return true;
        } catch (ExpiredJwtException e) {
            // 过期 Token：拒绝握手，返回 401；日志不记录完整 token
            log.warn("WebSocket 握手拒绝：token 已过期");
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        } catch (JwtException | IllegalArgumentException e) {
            // 无效/伪造 Token：拒绝握手，返回 401；不向客户端返回任何校验细节
            log.warn("WebSocket 握手拒绝：token 无效");
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
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