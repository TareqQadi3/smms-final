package com.smms.websocket;

import com.smms.auth.AuthSessionService;
import com.smms.auth.SessionTestSupport;
import com.smms.auth.WsTicketService;
import com.smms.entity.AuthSession;
import com.smms.entity.User;
import com.smms.enums.UserRole;
import com.smms.repository.UserRepository;
import com.smms.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * /ws/** is permitAll at the HTTP layer, so this interceptor is the only gate: every case that is
 * not a valid credential must be refused with 401 and must register nothing.
 */
class WsHandshakeInterceptorTest {

    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.now());
    private final WsTicketService tickets = new WsTicketService(now::get);
    private final SessionTestSupport sessions = new SessionTestSupport();
    private final UserRepository userRepo = mock(UserRepository.class);
    private final JwtService jwt = SessionTestSupport.jwt();
    private final WsHandshakeInterceptor interceptor =
        new WsHandshakeInterceptor(tickets, sessions.service, userRepo, jwt);

    private User user;
    private AuthSession session;
    private String ticket;

    @BeforeEach
    void setUp() {
        user = SessionTestSupport.user(UserRole.technician);
        when(userRepo.findById(user.getId())).thenReturn(Optional.of(user));
        session = sessions.service.create(user, "jti", Instant.now().plusSeconds(3600));
        ticket = tickets.issue(user.getId(), session.getId(), Instant.now().plusSeconds(600));
    }

    private record Result(boolean ok, int status, Map<String, Object> attrs) {}

    private Result handshake(String protocolHeader, String authorization, boolean authenticatedPrincipal) {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/ws/notifications");
        if (protocolHeader != null) req.addHeader("Sec-WebSocket-Protocol", protocolHeader);
        if (authorization != null) req.addHeader(HttpHeaders.AUTHORIZATION, authorization);
        if (authenticatedPrincipal) {
            req.setUserPrincipal(new UsernamePasswordAuthenticationToken(user, null, List.of()));
        }
        MockHttpServletResponse res = new MockHttpServletResponse();
        Map<String, Object> attrs = new HashMap<>();
        boolean ok = interceptor.beforeHandshake(new ServletServerHttpRequest(req), new ServletServerHttpResponse(res),
            mock(org.springframework.web.socket.WebSocketHandler.class), attrs);
        return new Result(ok, res.getStatus(), attrs);
    }

    private void assertRefused(Result r) {
        assertThat(r.ok()).isFalse();
        assertThat(r.status()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
        assertThat(r.attrs()).doesNotContainKey(NotificationWebSocketHandler.ATTR_USER_ID);
    }

    @Test
    void validTicketWithSubprotocolIsAccepted() {
        Result r = handshake("smms.v1, smms.ticket." + ticket, null, false);
        assertThat(r.ok()).isTrue();
        assertThat(r.attrs()).containsEntry(NotificationWebSocketHandler.ATTR_USER_ID, user.getId())
            .containsEntry(NotificationWebSocketHandler.ATTR_SID, session.getId())
            .containsKey(NotificationWebSocketHandler.ATTR_EXPIRES_AT);
    }

    @Test
    void ticketOfferedAsSeparateHeaderValuesIsAccepted() {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/ws/notifications");
        req.addHeader("Sec-WebSocket-Protocol", "smms.v1");
        req.addHeader("Sec-WebSocket-Protocol", "smms.ticket." + ticket);
        Map<String, Object> attrs = new HashMap<>();
        assertThat(interceptor.beforeHandshake(new ServletServerHttpRequest(req),
            new ServletServerHttpResponse(new MockHttpServletResponse()),
            mock(org.springframework.web.socket.WebSocketHandler.class), attrs)).isTrue();
    }

    @Test
    void noCredentialAtAllIsRefused() {
        assertRefused(handshake(null, null, false));
    }

    @Test
    void ticketInTheUrlIsNotConsidered() {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/ws/notifications");
        req.setQueryString("ticket=" + ticket + "&token=" + ticket + "&userId=" + user.getId());
        req.addParameter("ticket", ticket);
        MockHttpServletResponse res = new MockHttpServletResponse();
        Map<String, Object> attrs = new HashMap<>();
        assertThat(interceptor.beforeHandshake(new ServletServerHttpRequest(req), new ServletServerHttpResponse(res),
            mock(org.springframework.web.socket.WebSocketHandler.class), attrs)).isFalse();
        assertThat(res.getStatus()).isEqualTo(401);
    }

    @Test
    void reusedTicketIsRefused() {
        assertThat(handshake("smms.v1, smms.ticket." + ticket, null, false).ok()).isTrue();
        assertRefused(handshake("smms.v1, smms.ticket." + ticket, null, false));
    }

    @Test
    void expiredTicketIsRefused() {
        now.set(now.get().plusSeconds(WsTicketService.TTL_SECONDS + 1));
        assertRefused(handshake("smms.v1, smms.ticket." + ticket, null, false));
    }

    @Test
    void wrongFormatAndForgedTicketsAreRefused() {
        assertRefused(handshake("smms.v1, smms.ticket.", null, false));
        assertRefused(handshake("smms.v1, smms.ticket.short", null, false));
        assertRefused(handshake("smms.v1, smms.ticket." + "A".repeat(43), null, false));
        assertRefused(handshake("smms.v1, smms.ticket." + "../".repeat(15), null, false));
    }

    @Test
    void ticketWithoutTheSmmsSubprotocolIsRefused() {
        assertRefused(handshake("smms.ticket." + ticket, null, false));
        assertRefused(handshake("other, smms.ticket." + ticket, null, false));
    }

    @Test
    void twoTicketsInOneHandshakeAreRefused() {
        String second = tickets.issue(user.getId(), session.getId(), Instant.now().plusSeconds(600));
        assertRefused(handshake("smms.v1, smms.ticket." + ticket + ", smms.ticket." + second, null, false));
    }

    @Test
    void ticketOfARevokedSessionIsRefused() {
        sessions.service.revoke(session, AuthSessionService.REASON_LOGOUT);
        assertRefused(handshake("smms.v1, smms.ticket." + ticket, null, false));
    }

    @Test
    void ticketOfAnExpiredSessionIsRefused() {
        session.setExpiresAt(Instant.now().minusSeconds(1));
        assertRefused(handshake("smms.v1, smms.ticket." + ticket, null, false));
    }

    @Test
    void ticketOfASessionOfAnotherUserIsRefused() {
        User other = SessionTestSupport.user(UserRole.technician);
        AuthSession foreign = sessions.service.create(other, "j2", Instant.now().plusSeconds(3600));
        String t = tickets.issue(user.getId(), foreign.getId(), Instant.now().plusSeconds(600));
        assertRefused(handshake("smms.v1, smms.ticket." + t, null, false));
    }

    @Test
    void ticketOfADeactivatedUserIsRefused() {
        user.setActive(false);
        assertRefused(handshake("smms.v1, smms.ticket." + ticket, null, false));
    }

    @Test
    void ticketOfADeletedUserIsRefused() {
        user.setDeletedAt(Instant.now());
        assertRefused(handshake("smms.v1, smms.ticket." + ticket, null, false));
        when(userRepo.findById(user.getId())).thenReturn(Optional.empty());
        String t = tickets.issue(user.getId(), session.getId(), Instant.now().plusSeconds(600));
        assertRefused(handshake("smms.v1, smms.ticket." + t, null, false));
    }

    @Test
    void ticketIssuedBeforeAnAdminInvalidationIsRefused() {
        user.setTokensValidAfter(Instant.now().plusSeconds(5));
        assertRefused(handshake("smms.v1, smms.ticket." + ticket, null, false));
    }

    @Test
    void nonAdminWithoutTenantIsRefused() {
        user.setTenantId(null);
        assertRefused(handshake("smms.v1, smms.ticket." + ticket, null, false));
    }

    @Test
    void anyUnexpectedErrorFailsClosed() {
        when(userRepo.findById(user.getId())).thenThrow(new IllegalStateException("db down"));
        assertRefused(handshake("smms.v1, smms.ticket." + ticket, null, false));
    }

    @Test
    void nativeClientWithAuthenticatedPrincipalAndBearerIsAccepted() {
        String access = jwt.generateToken(user, session.getId());
        Result r = handshake(null, "Bearer " + access, true);
        assertThat(r.ok()).isTrue();
        assertThat(r.attrs()).containsEntry(NotificationWebSocketHandler.ATTR_USER_ID, user.getId())
            .containsEntry(NotificationWebSocketHandler.ATTR_SID, session.getId());
    }

    @Test
    void principalWithoutBearerHeaderIsRefused() {
        assertRefused(handshake(null, null, true));
    }

    @Test
    void bearerHeaderWithoutAnAuthenticatedPrincipalIsRefused() {
        // JwtAuthFilter did not authenticate it (invalid, revoked or expired token)
        assertRefused(handshake(null, "Bearer " + jwt.generateToken(user, session.getId()), false));
    }
}
