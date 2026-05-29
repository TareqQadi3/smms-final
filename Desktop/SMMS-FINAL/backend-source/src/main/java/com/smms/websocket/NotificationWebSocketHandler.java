package com.smms.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smms.entity.Notification;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

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
        UUID userId = extractUserId(session);
        if (userId != null) {
            sessions.computeIfAbsent(userId, k -> ConcurrentHashMap.newKeySet()).add(session);
            log.debug("WS connected: user={}", userId);
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

    private UUID extractUserId(WebSocketSession session) {
        String query = session.getUri() != null ? session.getUri().getQuery() : null;
        if (query != null && query.contains("userId=")) {
            try { return UUID.fromString(query.split("userId=")[1]); }
            catch (Exception ignored) {}
        }
        return null;
    }
}
