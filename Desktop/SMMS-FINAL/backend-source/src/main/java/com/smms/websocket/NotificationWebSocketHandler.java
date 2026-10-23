package com.smms.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smms.entity.Notification;
import com.smms.entity.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
@Slf4j
@RequiredArgsConstructor
public class NotificationWebSocketHandler extends TextWebSocketHandler {

    private final ObjectMapper objectMapper;
    private final Map<UUID, Set<WebSocketSession>> sessions = new ConcurrentHashMap<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        // Bind the socket to the authenticated principal only; any userId query parameter is ignored
        if (session.getPrincipal() instanceof Authentication auth && auth.getPrincipal() instanceof User user) {
            sessions.computeIfAbsent(user.getId(), k -> ConcurrentHashMap.newKeySet()).add(session);
            log.debug("WS connected: user={}", user.getId());
            return;
        }
        try {
            session.close(CloseStatus.POLICY_VIOLATION);
        } catch (IOException e) {
            log.warn("WS close of unauthenticated session failed", e);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.values().forEach(set -> set.remove(session));
    }

    public void sendToUser(UUID userId, Notification notification) {
        Set<WebSocketSession> userSessions = sessions.get(userId);
        if (userSessions == null) return;
        try {
            String payload = objectMapper.writeValueAsString(Map.of(
                "type", notification.getType(),
                "title", notification.getTitle(),
                "workOrderId", notification.getWorkOrder() != null
                    ? notification.getWorkOrder().getId() : "",
                "createdAt", notification.getCreatedAt().toString()
            ));
            for (WebSocketSession s : userSessions) {
                if (s.isOpen()) s.sendMessage(new TextMessage(payload));
            }
        } catch (Exception e) {
            log.error("WS send failed for user={}", userId, e);
        }
    }
}
