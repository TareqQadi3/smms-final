package com.smms.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smms.entity.Notification;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.net.URI;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class NotificationWebSocketHandlerTest {

    private final NotificationWebSocketHandler handler = new NotificationWebSocketHandler(new ObjectMapper());

    private static Notification notification() {
        Notification n = new Notification();
        n.setType("NEW_TICKET");
        n.setTitle("t");
        n.setCreatedAt(Instant.now());
        return n;
    }

    private static WebSocketSession socket(UUID userId, UUID sid, Instant expiresAt) throws Exception {
        Map<String, Object> attrs = new HashMap<>();
        if (userId != null) attrs.put(NotificationWebSocketHandler.ATTR_USER_ID, userId);
        if (sid != null) attrs.put(NotificationWebSocketHandler.ATTR_SID, sid);
        if (expiresAt != null) attrs.put(NotificationWebSocketHandler.ATTR_EXPIRES_AT, expiresAt);
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getAttributes()).thenReturn(attrs);
        when(session.getUri()).thenReturn(new URI("ws://x/ws/notifications"));
        when(session.isOpen()).thenReturn(true);
        return session;
    }

    @Test
    void bindsToAuthenticatedIdentityAndIgnoresUserIdQueryParam() throws Exception {
        UUID user = UUID.randomUUID();
        UUID victim = UUID.randomUUID();
        WebSocketSession session = socket(user, null, Instant.now().plusSeconds(60));
        when(session.getUri()).thenReturn(new URI("ws://x/ws/notifications?userId=" + victim));

        handler.afterConnectionEstablished(session);
        handler.sendToUser(user, notification());
        verify(session, times(1)).sendMessage(any(TextMessage.class));

        handler.sendToUser(victim, notification());
        verify(session, times(1)).sendMessage(any(TextMessage.class));
    }

    @Test
    void unauthenticatedSessionIsClosedAndNotRegistered() throws Exception {
        UUID victim = UUID.randomUUID();
        WebSocketSession session = socket(null, null, null);
        when(session.getUri()).thenReturn(new URI("ws://x/ws/notifications?userId=" + victim));

        handler.afterConnectionEstablished(session);
        handler.sendToUser(victim, notification());

        verify(session).close(any(CloseStatus.class));
        verify(session, never()).sendMessage(any(TextMessage.class));
    }

    @Test
    void announcesTheBrowserSubprotocol() {
        assertThat(handler.getSubProtocols()).containsExactly("smms.v1");
    }

    @Test
    void expiredSocketIsSkippedAndClosedOnSend() throws Exception {
        UUID user = UUID.randomUUID();
        WebSocketSession live = socket(user, null, Instant.now().plusSeconds(60));
        WebSocketSession stale = socket(user, null, Instant.now().plusMillis(30));
        handler.afterConnectionEstablished(live);
        handler.afterConnectionEstablished(stale);
        Thread.sleep(60);

        handler.sendToUser(user, notification());

        verify(stale, never()).sendMessage(any(TextMessage.class));
        verify(stale).close(any(CloseStatus.class));
        verify(live).sendMessage(any(TextMessage.class));
        assertThat(handler.openCount(user)).isEqualTo(1);
    }

    @Test
    void alreadyExpiredSocketIsRefusedAtConnect() throws Exception {
        UUID user = UUID.randomUUID();
        WebSocketSession s = socket(user, null, Instant.now().minusSeconds(1));
        handler.afterConnectionEstablished(s);
        verify(s).close(any(CloseStatus.class));
        assertThat(handler.openCount(user)).isZero();
    }

    @Test
    void closeSessionsClosesOnlyTheRevokedLoginSession() throws Exception {
        UUID user = UUID.randomUUID();
        UUID sidA = UUID.randomUUID();
        UUID sidB = UUID.randomUUID();
        WebSocketSession a = socket(user, sidA, Instant.now().plusSeconds(60));
        WebSocketSession b = socket(user, sidB, Instant.now().plusSeconds(60));
        handler.afterConnectionEstablished(a);
        handler.afterConnectionEstablished(b);

        handler.closeSessions(user, sidA);

        verify(a).close(any(CloseStatus.class));
        verify(b, never()).close(any(CloseStatus.class));
        handler.sendToUser(user, notification());
        verify(b).sendMessage(any(TextMessage.class));
        verify(a, never()).sendMessage(any(TextMessage.class));
    }

    @Test
    void closeSessionsWithoutSidClosesEveryUserSocketButNobodyElses() throws Exception {
        UUID user = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        WebSocketSession a = socket(user, UUID.randomUUID(), Instant.now().plusSeconds(60));
        WebSocketSession legacy = socket(user, null, Instant.now().plusSeconds(60));
        WebSocketSession bystander = socket(other, null, Instant.now().plusSeconds(60));
        List.of(a, legacy, bystander).forEach(handler::afterConnectionEstablished);

        handler.closeSessions(user, null);

        verify(a).close(any(CloseStatus.class));
        verify(legacy).close(any(CloseStatus.class));
        verify(bystander, never()).close(any(CloseStatus.class));
    }

    @Test
    void closeSessionsExceptKeepsTheCallersOwnSocket() throws Exception {
        UUID user = UUID.randomUUID();
        UUID keep = UUID.randomUUID();
        WebSocketSession mine = socket(user, keep, Instant.now().plusSeconds(60));
        WebSocketSession others = socket(user, UUID.randomUUID(), Instant.now().plusSeconds(60));
        handler.afterConnectionEstablished(mine);
        handler.afterConnectionEstablished(others);

        handler.closeSessionsExcept(user, keep);

        verify(mine, never()).close(any(CloseStatus.class));
        verify(others).close(any(CloseStatus.class));
    }

    @Test
    void sweepClosesExpiredSockets() throws Exception {
        UUID user = UUID.randomUUID();
        WebSocketSession stale = socket(user, null, Instant.now().plusMillis(30));
        handler.afterConnectionEstablished(stale);
        Thread.sleep(60);
        handler.closeExpired();
        verify(stale).close(any(CloseStatus.class));
    }
}
