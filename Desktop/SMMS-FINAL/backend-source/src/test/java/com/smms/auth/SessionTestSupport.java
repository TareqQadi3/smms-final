package com.smms.auth;

import com.smms.entity.AuthSession;
import com.smms.entity.User;
import com.smms.enums.UserRole;
import com.smms.repository.AuthSessionRepository;
import com.smms.security.JwtService;
import com.smms.websocket.NotificationWebSocketHandler;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** In-memory stand-in for the session table and the services around it (no database in unit tests). */
public class SessionTestSupport {

    public static final String TEST_SECRET = Base64.getEncoder().encodeToString(
        "smms-test-secret-key-for-unit-tests-must-be-long-enough-0123456789".getBytes());

    public final Map<UUID, AuthSession> store = new ConcurrentHashMap<>();
    public final AuthSessionRepository repo = mock(AuthSessionRepository.class);
    public final NotificationWebSocketHandler webSockets = mock(NotificationWebSocketHandler.class);
    public final AuthSessionService service = new AuthSessionService(repo, webSockets);

    public SessionTestSupport() {
        when(repo.save(any(AuthSession.class))).thenAnswer(i -> {
            AuthSession s = i.getArgument(0);
            store.put(s.getId(), s);
            return s;
        });
        when(repo.findById(any(UUID.class))).thenAnswer(i -> Optional.ofNullable(store.get(i.<UUID>getArgument(0))));
        when(repo.findByIdForUpdate(any(UUID.class))).thenAnswer(i -> Optional.ofNullable(store.get(i.<UUID>getArgument(0))));
        when(repo.countActive(any(UUID.class), any(UUID.class), any(Instant.class))).thenAnswer(i -> {
            AuthSession s = store.get(i.<UUID>getArgument(0));
            return s != null && s.getUserId().equals(i.getArgument(1)) && s.getRevokedAt() == null
                && s.getExpiresAt().isAfter(i.getArgument(2)) ? 1L : 0L;
        });
        when(repo.revokeAllForUser(any(UUID.class), any(Instant.class), anyString())).thenAnswer(i -> {
            int n = 0;
            for (AuthSession s : store.values()) {
                if (s.getUserId().equals(i.getArgument(0)) && s.getRevokedAt() == null) {
                    s.setRevokedAt(i.getArgument(1)); s.setRevokeReason(i.getArgument(2)); n++;
                }
            }
            return n;
        });
        when(repo.revokeOthersForUser(any(UUID.class), any(UUID.class), any(Instant.class), anyString())).thenAnswer(i -> {
            int n = 0;
            for (AuthSession s : store.values()) {
                if (s.getUserId().equals(i.getArgument(0)) && s.getRevokedAt() == null
                        && !s.getId().equals(i.getArgument(1))) {
                    s.setRevokedAt(i.getArgument(2)); s.setRevokeReason(i.getArgument(3)); n++;
                }
            }
            return n;
        });
    }

    public static JwtService jwt() {
        JwtService j = new JwtService();
        ReflectionTestUtils.setField(j, "secret", TEST_SECRET);
        ReflectionTestUtils.setField(j, "expiryHours", 8L);
        ReflectionTestUtils.setField(j, "refreshExpiryDays", 7L);
        return j;
    }

    public static User user(UserRole role) {
        User u = new User();
        u.setId(UUID.randomUUID());
        u.setUsername("u" + u.getId().toString().substring(0, 8));
        u.setPasswordHash("hash");
        u.setRole(role);
        u.setActive(true);
        if (role != UserRole.system_admin) u.setTenantId(UUID.randomUUID());
        return u;
    }
}
