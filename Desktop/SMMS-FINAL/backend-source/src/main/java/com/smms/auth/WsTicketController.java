package com.smms.auth;

import com.smms.entity.User;
import com.smms.exception.GlobalExceptionHandler;
import com.smms.security.JwtService;
import com.smms.util.CurrentUser;
import io.jsonwebtoken.JwtException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Issues the one-time ticket a browser offers as a WebSocket sub-protocol value. */
@RestController
@RequestMapping("/api/ws")
@RequiredArgsConstructor
public class WsTicketController {

    private final WsTicketService tickets;
    private final JwtService jwtService;

    @PostMapping("/ticket")
    @org.springframework.security.access.prepost.PreAuthorize("isAuthenticated()")
    public ResponseEntity<?> ticket(@CurrentUser User actor,
                                    @RequestHeader("Authorization") String authorization) {
        String token = authorization.startsWith("Bearer ") ? authorization.substring(7) : "";
        UUID sid;
        Instant exp;
        try {
            sid = jwtService.extractSessionId(token);
            exp = jwtService.extractExpiration(token);
        } catch (JwtException | IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(
                new GlobalExceptionHandler.ErrorBody(401, "Invalid token", Instant.now()));
        }
        try {
            String ticket = tickets.issue(actor.getId(), sid, exp);
            return ResponseEntity.ok()
                .header("Cache-Control", "no-store")
                .body(Map.of("ticket", ticket, "expiresInSeconds", WsTicketService.TTL_SECONDS));
        } catch (WsTicketService.CapacityExceededException e) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(
                new GlobalExceptionHandler.ErrorBody(429, "Too many pending tickets, retry shortly", Instant.now()));
        }
    }
}
