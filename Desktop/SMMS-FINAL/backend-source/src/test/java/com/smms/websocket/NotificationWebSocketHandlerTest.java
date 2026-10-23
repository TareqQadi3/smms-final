package com.smms.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smms.entity.Notification;
import com.smms.entity.User;
import com.smms.enums.UserRole;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class NotificationWebSocketHandlerTest {

    private static Notification notification() {
        Notification n = new Notification();
        n.setType("NEW_TICKET");
        n.setTitle("t");
        n.setCreatedAt(Instant.now());
        return n;
    }

    @Test
    void bindsToPrincipalAndIgnoresUserIdQueryParam() throws Exception {
        NotificationWebSocketHandler handler = new NotificationWebSocketHandler(new ObjectMapper());
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setRole(UserRole.resident);
        UUID victim = UUID.randomUUID();
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getPrincipal()).thenReturn(new UsernamePasswordAuthenticationToken(user, null, List.of()));
        when(session.getUri()).thenReturn(new URI("ws://x/ws/notifications?userId=" + victim));
        when(session.isOpen()).thenReturn(true);

        handler.afterConnectionEstablished(session);
        handler.sendToUser(user.getId(), notification());
        verify(session, times(1)).sendMessage(any(TextMessage.class));

        handler.sendToUser(victim, notification());
        verify(session, times(1)).sendMessage(any(TextMessage.class));
    }

    @Test
    void unauthenticatedSessionIsClosedAndNotRegistered() throws Exception {
        NotificationWebSocketHandler handler = new NotificationWebSocketHandler(new ObjectMapper());
        UUID victim = UUID.randomUUID();
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getPrincipal()).thenReturn(null);
        when(session.getUri()).thenReturn(new URI("ws://x/ws/notifications?userId=" + victim));
        when(session.isOpen()).thenReturn(true);

        handler.afterConnectionEstablished(session);
        handler.sendToUser(victim, notification());

        verify(session).close(CloseStatus.POLICY_VIOLATION);
        verify(session, never()).sendMessage(any(TextMessage.class));
    }
}
