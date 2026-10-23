package com.smms.websocket;

import com.smms.auth.AuthSessionService;
import com.smms.auth.WsTicketService;
import com.smms.entity.User;
import com.smms.enums.UserRole;
import com.smms.repository.UserRepository;
import com.smms.security.JwtService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.security.Principal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The only authentication gate of /ws/** (the HTTP layer permits the path so that a browser can
 * connect with a ticket). It FAILS CLOSED: unless the request carries an authenticated principal
 * from a valid Bearer header, or a valid single-use ticket in the Sec-WebSocket-Protocol header,
 * the handshake is refused with 401 and nothing is registered. Nothing is read from the URL.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WsHandshakeInterceptor implements HandshakeInterceptor {

    private final WsTicketService tickets;
    private final AuthSessionService sessions;
    private final UserRepository userRepo;
    private final JwtService jwtService;

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        try {
            boolean ok = fromPrincipal(request, attributes) || fromTicket(request, attributes);
            if (!ok) {
                log.warn("SECURITY: WebSocket handshake refused (no valid credential)");
                response.setStatusCode(HttpStatus.UNAUTHORIZED);
            }
            return ok;
        } catch (RuntimeException e) {
            log.warn("SECURITY: WebSocket handshake refused ({})", e.getClass().getSimpleName());
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
        // nothing to do
    }

    /** Native clients: the Authorization header already went through JwtAuthFilter. */
    private boolean fromPrincipal(ServerHttpRequest request, Map<String, Object> attributes) {
        Principal p = request.getPrincipal();
        if (!(p instanceof Authentication auth) || !auth.isAuthenticated()
                || !(auth.getPrincipal() instanceof User user) || user.getId() == null) {
            return false;
        }
        String header = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith("Bearer ")) return false;
        String token = header.substring(7);
        try {
            attributes.put(NotificationWebSocketHandler.ATTR_USER_ID, user.getId());
            UUID sid = jwtService.extractSessionId(token);
            if (sid != null) attributes.put(NotificationWebSocketHandler.ATTR_SID, sid);
            attributes.put(NotificationWebSocketHandler.ATTR_EXPIRES_AT, jwtService.extractExpiration(token));
            return true;
        } catch (RuntimeException e) {
            attributes.clear();
            return false;
        }
    }

    /** Browsers: offered protocols are "smms.v1" and "smms.ticket.&lt;ticket&gt;". */
    private boolean fromTicket(ServerHttpRequest request, Map<String, Object> attributes) {
        List<String> offered = offeredProtocols(request);
        if (!offered.contains(NotificationWebSocketHandler.SUBPROTOCOL)) return false;
        String ticketValue = null;
        for (String o : offered) {
            if (o.startsWith(NotificationWebSocketHandler.TICKET_PROTOCOL_PREFIX)) {
                if (ticketValue != null) return false; // more than one ticket offered
                ticketValue = o.substring(NotificationWebSocketHandler.TICKET_PROTOCOL_PREFIX.length());
            }
        }
        if (ticketValue == null) return false;
        Optional<WsTicketService.Ticket> consumed = tickets.consume(ticketValue);
        if (consumed.isEmpty()) return false;
        WsTicketService.Ticket t = consumed.get();

        User user = userRepo.findById(t.userId()).orElse(null);
        if (user == null || !user.isEnabled()) return false;
        if (user.getRole() != UserRole.system_admin && user.getTenantId() == null) return false;
        Instant validAfter = user.getTokensValidAfter();
        if (validAfter != null && t.issuedAt().isBefore(validAfter)) return false;
        if (t.sid() != null && !sessions.isActive(t.sid(), user.getId())) return false;

        attributes.put(NotificationWebSocketHandler.ATTR_USER_ID, user.getId());
        if (t.sid() != null) attributes.put(NotificationWebSocketHandler.ATTR_SID, t.sid());
        attributes.put(NotificationWebSocketHandler.ATTR_EXPIRES_AT, t.accessExpiresAt());
        return true;
    }

    private static List<String> offeredProtocols(ServerHttpRequest request) {
        List<String> out = new ArrayList<>();
        List<String> headers = request.getHeaders().get("Sec-WebSocket-Protocol");
        if (headers == null) return out;
        for (String h : headers) {
            for (String part : h.split(",")) {
                String v = part.trim();
                if (!v.isEmpty()) out.add(v);
            }
        }
        return out;
    }
}
