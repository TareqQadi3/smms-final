package com.smms.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smms.entity.Notification;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.SubProtocolCapable;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Pushes notifications to connected users. A socket is bound to the identity that the handshake
 * interceptor authenticated (attributes), never to anything the client sends afterwards. Sockets are
 * closed when their login session is revoked, the account is deactivated, or the access token that
 * opened them expires.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class NotificationWebSocketHandler extends TextWebSocketHandler implements SubProtocolCapable {

    /** Sub-protocol every browser client must offer; the ticket travels as a second offered value. */
    public static final String SUBPROTOCOL = "smms.v1";
    public static final String TICKET_PROTOCOL_PREFIX = "smms.ticket.";

    public static final String ATTR_USER_ID = "smms.userId";
    public static final String ATTR_SID = "smms.sid";
    public static final String ATTR_EXPIRES_AT = "smms.expiresAt";

    private final ObjectMapper objectMapper;
    private final Map<UUID, Set<WebSocketSession>> sessions = new ConcurrentHashMap<>();

    @Override
    public List<String> getSubProtocols() { return List.of(SUBPROTOCOL); }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        // Bind the socket to the identity set by the handshake interceptor only; any userId query
        // parameter is ignored. No attribute means the interceptor did not authenticate it.
        Object userId = session.getAttributes().get(ATTR_USER_ID);
        if (userId instanceof UUID id && !isExpired(session)) {
            sessions.computeIfAbsent(id, k -> ConcurrentHashMap.newKeySet()).add(session);
            log.debug("WS connected: user={}", id);
            return;
        }
        close(session, "unauthenticated");
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
                if (isExpired(s)) { drop(userId, s, "token expired"); continue; }
                if (s.isOpen()) s.sendMessage(new TextMessage(payload));
            }
        } catch (Exception e) {
            log.error("WS send failed for user={}", userId, e);
        }
    }

    /** Closes the user's sockets of one login session, or all of them when {@code sid} is null. */
    public void closeSessions(UUID userId, UUID sid) {
        closeMatching(userId, s -> sid == null || sid.equals(s.getAttributes().get(ATTR_SID)), "session revoked");
    }

    /** Closes every socket of the user except those of {@code keepSid} (password change). */
    public void closeSessionsExcept(UUID userId, UUID keepSid) {
        closeMatching(userId, s -> !Objects.equals(keepSid, s.getAttributes().get(ATTR_SID)), "session revoked");
    }

    /** Closes sockets whose access token has expired; called periodically. */
    public void closeExpired() {
        sessions.forEach((userId, set) -> closeMatching(userId, this::isExpired, "token expired"));
    }

    int openCount(UUID userId) {
        Set<WebSocketSession> set = sessions.get(userId);
        return set == null ? 0 : set.size();
    }

    private void closeMatching(UUID userId, java.util.function.Predicate<WebSocketSession> match, String reason) {
        Set<WebSocketSession> set = sessions.get(userId);
        if (set == null) return;
        for (WebSocketSession s : set) {
            if (match.test(s)) drop(userId, s, reason);
        }
    }

    private void drop(UUID userId, WebSocketSession s, String reason) {
        Set<WebSocketSession> set = sessions.get(userId);
        if (set != null) set.remove(s);
        close(s, reason);
    }

    private boolean isExpired(WebSocketSession s) {
        Object exp = s.getAttributes().get(ATTR_EXPIRES_AT);
        return !(exp instanceof Instant at) || !at.isAfter(Instant.now());
    }

    private void close(WebSocketSession s, String reason) {
        try {
            s.close(CloseStatus.POLICY_VIOLATION.withReason(reason));
        } catch (IOException | RuntimeException e) {
            log.warn("WS close failed ({})", reason, e);
        }
    }
}
