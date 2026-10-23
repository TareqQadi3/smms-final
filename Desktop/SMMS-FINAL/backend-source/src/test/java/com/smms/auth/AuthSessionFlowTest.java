package com.smms.auth;

import com.smms.dto.request.LoginRequest;
import com.smms.dto.response.AuthResponse;
import com.smms.entity.AuthSession;
import com.smms.entity.User;
import com.smms.enums.UserRole;
import com.smms.exception.AuthException;
import com.smms.repository.TenantRepository;
import com.smms.repository.UserRepository;
import com.smms.security.BruteForceProtectionService;
import com.smms.security.JwtService;
import com.smms.service.AuthService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/** Refresh rotation, reuse detection, logout and revocation against an in-memory session store. */
class AuthSessionFlowTest {

    private SessionTestSupport sessions;
    private JwtService jwt;
    private UserRepository userRepo;
    private PasswordEncoder encoder;
    private AuthService service;
    private User user;

    @BeforeEach
    void setUp() {
        sessions = new SessionTestSupport();
        jwt = SessionTestSupport.jwt();
        userRepo = mock(UserRepository.class);
        encoder = mock(PasswordEncoder.class);
        service = new AuthService(userRepo, mock(TenantRepository.class), jwt, encoder,
            mock(BruteForceProtectionService.class), sessions.service);
        user = SessionTestSupport.user(UserRole.technician);
        user.setUsername("tech1");
        when(userRepo.findByUsernameAndDeletedAtIsNull("tech1")).thenReturn(Optional.of(user));
        when(userRepo.findById(user.getId())).thenReturn(Optional.of(user));
        when(encoder.matches(anyString(), eq("hash"))).thenAnswer(i -> "pw".equals(i.getArgument(0)) || "Admin@1234".equals(i.getArgument(0)));
    }

    private AuthResponse login(String password) {
        LoginRequest r = new LoginRequest();
        r.setUsername("tech1");
        r.setPassword(password);
        return service.login(r);
    }

    private static void sleepPastSecond() throws InterruptedException { Thread.sleep(1100); }

    // ── login ───────────────────────────────────────────────────

    @Test
    void loginCreatesSessionAndBindsBothTokens() {
        AuthResponse r = login("pw");
        assertThat(sessions.store).hasSize(1);
        AuthSession s = sessions.store.values().iterator().next();
        assertThat(jwt.extractSessionId(r.getToken())).isEqualTo(s.getId());
        assertThat(jwt.extractSessionId(r.getRefreshToken())).isEqualTo(s.getId());
        assertThat(jwt.isAccessToken(r.getToken())).isTrue();
        assertThat(jwt.isRefreshToken(r.getRefreshToken())).isTrue();
        // only the hash of the refresh jti is stored
        String jti = jwt.extractJti(r.getRefreshToken());
        assertThat(s.getCurrentJtiHash()).isEqualTo(AuthSessionService.hash(jti)).isNotEqualTo(jti);
        assertThat(s.getPreviousJtiHash()).isNull();
        assertThat(s.getExpiresAt()).isAfter(Instant.now().plusSeconds(6 * 86400));
    }

    @Test
    void passwordChangeRequiredFlagFollowsFlagOrPublicDefault() {
        assertThat(login("pw").isPasswordChangeRequired()).isFalse();
        assertThat(login("Admin@1234").isPasswordChangeRequired()).isTrue();
        user.setMustChangePassword(true);
        assertThat(login("pw").isPasswordChangeRequired()).isTrue();
    }

    // ── rotation ────────────────────────────────────────────────

    @Test
    void refreshRotatesAndReturnsTheFullLoginShapedBody() {
        AuthResponse first = login("pw");
        AuthResponse second = service.refresh(first.getRefreshToken());
        assertThat(second.getRefreshToken()).isNotEqualTo(first.getRefreshToken());
        assertThat(second.getToken()).isNotBlank();
        assertThat(second.getUserId()).isEqualTo(user.getId());
        assertThat(second.getRole()).isEqualTo(UserRole.technician);
        assertThat(second.getTenantId()).isNull(); // tenantRepo mock returns empty; field is populated from the repo
        AuthSession s = sessions.store.values().iterator().next();
        assertThat(s.getPreviousJtiHash()).isEqualTo(AuthSessionService.hash(jwt.extractJti(first.getRefreshToken())));
        assertThat(s.getCurrentJtiHash()).isEqualTo(AuthSessionService.hash(jwt.extractJti(second.getRefreshToken())));
        assertThat(s.getLastRefreshedAt()).isNotNull();
        // the new tokens belong to the same session
        assertThat(jwt.extractSessionId(second.getToken())).isEqualTo(s.getId());
    }

    @Test
    void rotationDoesNotExtendTheAbsoluteLifetime() {
        AuthResponse first = login("pw");
        AuthSession s = sessions.store.values().iterator().next();
        Instant end = s.getExpiresAt();
        AuthResponse second = service.refresh(first.getRefreshToken());
        assertThat(s.getExpiresAt()).isEqualTo(end);
        assertThat(jwt.extractExpiration(second.getRefreshToken()).getEpochSecond()).isEqualTo(end.getEpochSecond());
    }

    @Test
    void chainOfRefreshesKeepsWorking() {
        String rt = login("pw").getRefreshToken();
        for (int i = 0; i < 3; i++) rt = service.refresh(rt).getRefreshToken();
        assertThat(sessions.store).hasSize(1);
    }

    @Test
    void refreshedAccessTokenCarriesTheMustChangeFlagOfTheAccount() {
        user.setMustChangePassword(true);
        assertThat(service.refresh(login("pw").getRefreshToken()).isPasswordChangeRequired()).isTrue();
    }

    // ── reuse detection ─────────────────────────────────────────

    @Test
    void reusingARotatedRefreshTokenRevokesTheWholeSession() {
        AuthResponse first = login("pw");
        AuthResponse second = service.refresh(first.getRefreshToken());

        assertThatThrownBy(() -> service.refresh(first.getRefreshToken())).isInstanceOf(AuthException.class);

        AuthSession s = sessions.store.values().iterator().next();
        assertThat(s.getRevokedAt()).isNotNull();
        assertThat(s.getRevokeReason()).isEqualTo(AuthSessionService.REASON_REUSE);
        // the legitimate holder of the newest token is cut off too (no grace window)
        assertThatThrownBy(() -> service.refresh(second.getRefreshToken())).isInstanceOf(AuthException.class);
        assertThat(sessions.service.isActive(s.getId(), user.getId())).isFalse();
        verify(sessions.webSockets).closeSessions(user.getId(), s.getId());
    }

    @Test
    void secondRefreshWithTheSameTokenIsRejectedAndRevokes() {
        // what a concurrent double refresh looks like after the row lock serialised it
        String rt = login("pw").getRefreshToken();
        service.refresh(rt);
        assertThatThrownBy(() -> service.refresh(rt)).isInstanceOf(AuthException.class);
        assertThat(sessions.store.values().iterator().next().isRevoked()).isTrue();
    }

    @Test
    void refreshRevocationMustCommitDespiteTheThrownAuthException() throws Exception {
        Transactional tx = AuthService.class.getMethod("refresh", String.class).getAnnotation(Transactional.class);
        assertThat(tx).isNotNull();
        assertThat(tx.noRollbackFor()).contains(AuthException.class);
    }

    @Test
    void refreshLocksTheSessionRow() {
        service.refresh(login("pw").getRefreshToken());
        verify(sessions.repo).findByIdForUpdate(any());
    }

    @Test
    void otherSessionsOfTheSameUserSurviveAReuseIncident() {
        AuthResponse a = login("pw");
        AuthResponse b = login("pw");
        service.refresh(a.getRefreshToken());
        assertThatThrownBy(() -> service.refresh(a.getRefreshToken())).isInstanceOf(AuthException.class);
        assertThat(service.refresh(b.getRefreshToken()).getToken()).isNotBlank();
    }

    // ── invalid refresh inputs ──────────────────────────────────

    @Test
    void accessTokenCannotBeUsedAsRefreshToken() {
        AuthResponse r = login("pw");
        assertThatThrownBy(() -> service.refresh(r.getToken())).isInstanceOf(AuthException.class);
        AuthSession s = sessions.store.values().iterator().next();
        assertThat(s.isRevoked()).isFalse();
    }

    @Test
    void legacyRefreshTokenWithoutSessionIsRefused() {
        String legacy = jwt.generateRefreshToken(user);
        assertThatThrownBy(() -> service.refresh(legacy)).isInstanceOf(AuthException.class);
    }

    @Test
    void garbageAndTamperedRefreshTokensAreRefusedAs401Domain() {
        assertThatThrownBy(() -> service.refresh("not.a.jwt")).isInstanceOf(AuthException.class);
        assertThatThrownBy(() -> service.refresh("")).isInstanceOf(AuthException.class);
        String rt = login("pw").getRefreshToken();
        assertThatThrownBy(() -> service.refresh(rt.substring(0, rt.length() - 3) + "abc")).isInstanceOf(AuthException.class);
    }

    @Test
    void refreshWithUnknownSessionIsRefused() {
        String rt = login("pw").getRefreshToken();
        sessions.store.clear();
        assertThatThrownBy(() -> service.refresh(rt)).isInstanceOf(AuthException.class);
    }

    @Test
    void expiredSessionCannotRefresh() {
        String rt = login("pw").getRefreshToken();
        sessions.store.values().iterator().next().setExpiresAt(Instant.now().minusSeconds(1));
        assertThatThrownBy(() -> service.refresh(rt)).isInstanceOf(AuthException.class);
    }

    @Test
    void expiredRefreshTokenIsRefused() {
        AuthSession s = sessions.service.create(user, "jti-x", Instant.now().minusSeconds(5));
        String expired = jwt.generateRefreshToken(user, s.getId(), "jti-x", Instant.now().minusSeconds(5));
        assertThatThrownBy(() -> service.refresh(expired)).isInstanceOf(AuthException.class);
    }

    @Test
    void refreshTokenOfAnotherUsersSessionIsRefused() {
        User other = SessionTestSupport.user(UserRole.technician);
        other.setUsername("other");
        AuthSession foreign = sessions.service.create(other, "jt", Instant.now().plusSeconds(600));
        // token names tech1 but points at someone else's session
        String forged = jwt.generateRefreshToken(user, foreign.getId(), "jt", Instant.now().plusSeconds(600));
        assertThatThrownBy(() -> service.refresh(forged)).isInstanceOf(AuthException.class);
    }

    @Test
    void deactivatedUserCannotRefreshAndSessionIsRevoked() {
        String rt = login("pw").getRefreshToken();
        user.setActive(false);
        assertThatThrownBy(() -> service.refresh(rt)).isInstanceOf(AuthException.class);
        assertThat(sessions.store.values().iterator().next().isRevoked()).isTrue();
    }

    @Test
    void deletedUserCannotRefresh() {
        String rt = login("pw").getRefreshToken();
        when(userRepo.findByUsernameAndDeletedAtIsNull("tech1")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.refresh(rt)).isInstanceOf(AuthException.class);
    }

    @Test
    void tokensIssuedBeforeAdminInvalidationCannotRefresh() throws Exception {
        String rt = login("pw").getRefreshToken();
        sleepPastSecond();
        user.setTokensValidAfter(Instant.now());
        assertThatThrownBy(() -> service.refresh(rt)).isInstanceOf(AuthException.class);
        // a fresh login after the invalidation works
        assertThat(login("pw").getRefreshToken()).isNotBlank();
    }

    @Test
    void tenantlessNonAdminCannotRefresh() {
        String rt = login("pw").getRefreshToken();
        user.setTenantId(null);
        assertThatThrownBy(() -> service.refresh(rt)).isInstanceOf(AuthException.class);
    }

    // ── logout ──────────────────────────────────────────────────

    @Test
    void logoutRevokesTheSessionOfTheAccessToken() {
        AuthResponse r = login("pw");
        service.logout("Bearer " + r.getToken(), null);
        AuthSession s = sessions.store.values().iterator().next();
        assertThat(s.getRevokeReason()).isEqualTo(AuthSessionService.REASON_LOGOUT);
        assertThatThrownBy(() -> service.refresh(r.getRefreshToken())).isInstanceOf(AuthException.class);
        verify(sessions.webSockets).closeSessions(user.getId(), s.getId());
    }

    @Test
    void logoutLeavesOtherSessionsOfTheUserAlone() {
        AuthResponse a = login("pw");
        AuthResponse b = login("pw");
        service.logout("Bearer " + a.getToken(), a.getRefreshToken());
        assertThat(sessions.service.isActive(jwt.extractSessionId(b.getToken()), user.getId())).isTrue();
        assertThat(service.refresh(b.getRefreshToken()).getToken()).isNotBlank();
        assertThat(sessions.service.isActive(jwt.extractSessionId(a.getToken()), user.getId())).isFalse();
    }

    @Test
    void logoutWithRefreshHeaderOnlyRevokesThatSession() {
        AuthResponse r = login("pw");
        service.logout(null, r.getRefreshToken());
        assertThat(sessions.store.values().iterator().next().isRevoked()).isTrue();
    }

    @Test
    void logoutIgnoresARefreshTokenOfAnotherUser() {
        User other = SessionTestSupport.user(UserRole.technician);
        other.setUsername("other");
        when(userRepo.findByUsernameAndDeletedAtIsNull("other")).thenReturn(Optional.of(other));
        when(userRepo.findById(other.getId())).thenReturn(Optional.of(other));
        LoginRequest lr = new LoginRequest();
        lr.setUsername("other"); lr.setPassword("pw");
        AuthResponse victim = service.login(lr);
        AuthResponse attacker = login("pw");

        service.logout("Bearer " + attacker.getToken(), victim.getRefreshToken());

        assertThat(sessions.service.isActive(jwt.extractSessionId(victim.getToken()), other.getId())).isTrue();
        assertThat(sessions.service.isActive(jwt.extractSessionId(attacker.getToken()), user.getId())).isFalse();
    }

    @Test
    void logoutNeverThrowsAndGivesNoOracle() {
        service.logout(null, null);
        service.logout("Bearer garbage", "garbage");
        service.logout("Basic abc", "");
        // an access token passed as X-Refresh-Token and vice versa revoke nothing
        AuthResponse r = login("pw");
        service.logout("Bearer " + r.getRefreshToken(), r.getToken());
        assertThat(sessions.store.values().iterator().next().isRevoked()).isFalse();
    }

    @Test
    void logoutWithAnExpiredAccessTokenStillEndsItsSession() {
        AuthSession s = sessions.service.create(user, "j", Instant.now().plusSeconds(600));
        // sign an already-expired access token for the session through the same key
        String expired = io.jsonwebtoken.Jwts.builder().subject("tech1")
            .claim("typ", "access").claim("role", "technician").claim("sid", s.getId().toString())
            .issuedAt(new java.util.Date(System.currentTimeMillis() - 20_000))
            .expiration(new java.util.Date(System.currentTimeMillis() - 10_000))
            .signWith(io.jsonwebtoken.security.Keys.hmacShaKeyFor(
                io.jsonwebtoken.io.Decoders.BASE64.decode(SessionTestSupport.TEST_SECRET))).compact();
        service.logout("Bearer " + expired, null);
        assertThat(s.isRevoked()).isTrue();
    }

    // ── session service ─────────────────────────────────────────

    @Test
    void sessionIsActiveOnlyForItsOwnUserWhileLiveAndUnrevoked() {
        AuthSession s = sessions.service.create(user, "j", Instant.now().plusSeconds(60));
        assertThat(sessions.service.isActive(s.getId(), user.getId())).isTrue();
        assertThat(sessions.service.isActive(s.getId(), java.util.UUID.randomUUID())).isFalse();
        assertThat(sessions.service.isActive(java.util.UUID.randomUUID(), user.getId())).isFalse();
        assertThat(sessions.service.isActive(null, user.getId())).isFalse();
        s.setExpiresAt(Instant.now().minusSeconds(1));
        assertThat(sessions.service.isActive(s.getId(), user.getId())).isFalse();
    }

    @Test
    void revokeAllAndRevokeOthers() {
        AuthSession a = sessions.service.create(user, "a", Instant.now().plusSeconds(60));
        AuthSession b = sessions.service.create(user, "b", Instant.now().plusSeconds(60));
        sessions.service.revokeOthers(user.getId(), a.getId(), AuthSessionService.REASON_PASSWORD_CHANGED);
        assertThat(a.isRevoked()).isFalse();
        assertThat(b.isRevoked()).isTrue();
        verify(sessions.webSockets).closeSessionsExcept(user.getId(), a.getId());
        sessions.service.revokeAllForUser(user.getId(), AuthSessionService.REASON_DEACTIVATED);
        assertThat(a.isRevoked()).isTrue();
        verify(sessions.webSockets).closeSessions(user.getId(), null);
    }

    @Test
    void jtiHashIsSha256Hex() {
        assertThat(AuthSessionService.hash("abc"))
            .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    @Test
    void newJtisAreRandomAndUrlSafe() {
        String a = sessions.service.newJti();
        assertThat(a).matches("^[A-Za-z0-9_-]{32}$").isNotEqualTo(sessions.service.newJti());
    }
}
