package com.smms.auth;

import com.smms.entity.AuthSession;
import com.smms.entity.User;
import com.smms.repository.AuthSessionRepository;
import com.smms.websocket.NotificationWebSocketHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

/** Login sessions behind the refresh tokens: create, validate per request, rotate, revoke. */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthSessionService {

    public static final String REASON_LOGOUT = "logout";
    public static final String REASON_REUSE = "reuse";
    public static final String REASON_DEACTIVATED = "deactivated";
    public static final String REASON_PASSWORD_RESET = "password_reset";
    public static final String REASON_PASSWORD_CHANGED = "password_changed";

    private final AuthSessionRepository repo;
    private final NotificationWebSocketHandler webSockets;
    private final SecureRandom random = new SecureRandom();

    /** Fresh random refresh-token id (the token carries it, only its hash is stored). */
    public String newJti() {
        byte[] raw = new byte[24];
        random.nextBytes(raw);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    public static String hash(String jti) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(jti.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public AuthSession create(User user, String jti, Instant expiresAt) {
        return repo.save(AuthSession.builder()
            .id(UUID.randomUUID())
            .userId(user.getId())
            .tenantId(user.getTenantId())
            .currentJtiHash(hash(jti))
            .createdAt(Instant.now())
            .expiresAt(expiresAt)
            .build());
    }

    /** Per-request check: the session exists, belongs to the user, is not revoked and has not expired. */
    @Transactional(readOnly = true)
    public boolean isActive(UUID sid, UUID userId) {
        return sid != null && userId != null && repo.countActive(sid, userId, Instant.now()) > 0;
    }

    public Optional<AuthSession> find(UUID sid) { return repo.findById(sid); }

    /** Loads the session under a row lock; call inside a transaction. */
    public Optional<AuthSession> lockForRefresh(UUID sid) { return repo.findByIdForUpdate(sid); }

    public void rotate(AuthSession s, String newJti) {
        s.setPreviousJtiHash(s.getCurrentJtiHash());
        s.setCurrentJtiHash(hash(newJti));
        s.setLastRefreshedAt(Instant.now());
        repo.save(s);
    }

    public void revoke(AuthSession s, String reason) {
        if (s.getRevokedAt() == null) {
            s.setRevokedAt(Instant.now());
            s.setRevokeReason(reason);
            repo.save(s);
        }
        webSockets.closeSessions(s.getUserId(), s.getId());
    }

    /** Deactivation, admin reset: every session of the user ends and their sockets close. */
    public void revokeAllForUser(UUID userId, String reason) {
        repo.revokeAllForUser(userId, Instant.now(), reason);
        webSockets.closeSessions(userId, null);
    }

    /** Password change: the current login stays, all others end. A null keep revokes everything. */
    public void revokeOthers(UUID userId, UUID keepSid, String reason) {
        if (keepSid == null) { revokeAllForUser(userId, reason); return; }
        repo.revokeOthersForUser(userId, keepSid, Instant.now(), reason);
        webSockets.closeSessionsExcept(userId, keepSid);
    }

    /** Sessions that expired more than 30 days ago are deleted. */
    public int purgeExpired() {
        return repo.deleteExpiredBefore(Instant.now().minusSeconds(30L * 86400));
    }
}
