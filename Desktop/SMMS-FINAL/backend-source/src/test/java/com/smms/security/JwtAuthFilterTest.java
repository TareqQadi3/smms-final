package com.smms.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smms.cache.SubscriptionCache;
import com.smms.auth.SessionTestSupport;
import com.smms.entity.User;
import com.smms.enums.UserRole;
import com.smms.repository.TenantSubscriptionRepository;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class JwtAuthFilterTest {

    private static final String TEST_SECRET = Base64.getEncoder().encodeToString(
        "smms-test-secret-key-for-unit-tests-must-be-long-enough-0123456789".getBytes());

    private JwtService jwtService;
    private JwtAuthFilter filter;
    private User user;
    private SessionTestSupport sessions;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        sessions = new SessionTestSupport();
        jwtService = new JwtService();
        ReflectionTestUtils.setField(jwtService, "secret", TEST_SECRET);
        ReflectionTestUtils.setField(jwtService, "expiryHours", 8L);
        ReflectionTestUtils.setField(jwtService, "refreshExpiryDays", 7L);

        user = new User();
        user.setId(java.util.UUID.randomUUID());
        user.setUsername("tech1");
        user.setRole(UserRole.technician);
        user.setTenantId(java.util.UUID.randomUUID());

        UserDetailsService uds = mock(UserDetailsService.class);
        when(uds.loadUserByUsername("tech1")).thenReturn(user);
        filter = new JwtAuthFilter(jwtService, uds, mock(TenantSubscriptionRepository.class),
            mock(SubscriptionCache.class), new ObjectMapper().findAndRegisterModules(), sessions.service);
    }

    @AfterEach
    void tearDown() { SecurityContextHolder.clearContext(); }

    private boolean authenticatedWith(String token) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/users/me");
        req.addHeader("Authorization", "Bearer " + token);
        FilterChain chain = mock(FilterChain.class);
        boolean[] authed = new boolean[1];
        doAnswer(inv -> {
            authed[0] = SecurityContextHolder.getContext().getAuthentication() != null;
            return null;
        }).when(chain).doFilter(any(), any());
        filter.doFilter(req, new MockHttpServletResponse(), chain);
        return authed[0];
    }

    @Test
    void accessTokenAuthenticates() throws Exception {
        assertThat(authenticatedWith(jwtService.generateToken(user))).isTrue();
    }

    @Test
    void refreshTokenIsNotAuthenticatedAsBearer() throws Exception {
        assertThat(authenticatedWith(jwtService.generateRefreshToken(user))).isFalse();
    }

    @Test
    void nonAdminTokenWithEmptyTenantClaimIsNotAuthenticated() throws Exception {
        user.setTenantId(null);
        // token issued while the user had no tenant: claim is empty
        assertThat(authenticatedWith(jwtService.generateToken(user))).isFalse();
    }

    @Test
    void nonAdminWhoseTenantWasAssignedLaterNeedsAFreshToken() throws Exception {
        java.util.UUID assigned = user.getTenantId();
        user.setTenantId(null);
        String oldToken = jwtService.generateToken(user);
        user.setTenantId(assigned);
        assertThat(authenticatedWith(oldToken)).isFalse();
        assertThat(authenticatedWith(jwtService.generateToken(user))).isTrue();
    }

    @Test
    void tokenTenantDifferentFromStoredTenantIsNotAuthenticated() throws Exception {
        String token = jwtService.generateToken(user);
        user.setTenantId(java.util.UUID.randomUUID()); // account moved to another tenant
        assertThat(authenticatedWith(token)).isFalse();
    }

    @Test
    void systemAdminWithEmptyTenantAuthenticates() throws Exception {
        user.setRole(UserRole.system_admin);
        user.setTenantId(null);
        assertThat(authenticatedWith(jwtService.generateToken(user))).isTrue();
    }

    @Test
    void systemAdminTokenWithTenantClaimThatNoLongerMatchesIsRejected() throws Exception {
        user.setRole(UserRole.system_admin);
        user.setTenantId(java.util.UUID.randomUUID());
        String token = jwtService.generateToken(user);
        user.setTenantId(null);
        assertThat(authenticatedWith(token)).isFalse();
    }

    @Test
    void malformedOrTamperedTokenContinuesUnauthenticatedWithoutError() throws Exception {
        assertThat(authenticatedWith("not.a.jwt")).isFalse();
        String t = jwtService.generateToken(user);
        assertThat(authenticatedWith(t.substring(0, t.length() - 3) + "abc")).isFalse();
    }

    @Test
    void tokenSignedWithAnotherKeyIsNotAuthenticated() throws Exception {
        JwtService other = new JwtService();
        byte[] k = new byte[48];
        new java.security.SecureRandom().nextBytes(k);
        ReflectionTestUtils.setField(other, "secret", Base64.getEncoder().encodeToString(k));
        ReflectionTestUtils.setField(other, "expiryHours", 8L);
        assertThat(authenticatedWith(other.generateToken(user))).isFalse();
    }

    // ── P4: account state, sessions, invalidation ───────────────

    private com.smms.entity.AuthSession session() {
        return sessions.service.create(user, "jti", java.time.Instant.now().plusSeconds(3600));
    }

    @Test
    void tokenBoundToALiveSessionAuthenticates() throws Exception {
        assertThat(authenticatedWith(jwtService.generateToken(user, session().getId()))).isTrue();
    }

    @Test
    void tokenOfARevokedSessionIsRejected() throws Exception {
        var s = session();
        String token = jwtService.generateToken(user, s.getId());
        assertThat(authenticatedWith(token)).isTrue();
        SecurityContextHolder.clearContext();
        sessions.service.revoke(s, "logout");
        assertThat(authenticatedWith(token)).isFalse();
    }

    @Test
    void tokenOfAnExpiredSessionIsRejected() throws Exception {
        var s = session();
        s.setExpiresAt(java.time.Instant.now().minusSeconds(1));
        assertThat(authenticatedWith(jwtService.generateToken(user, s.getId()))).isFalse();
    }

    @Test
    void tokenWithUnknownSessionIsRejected() throws Exception {
        assertThat(authenticatedWith(jwtService.generateToken(user, java.util.UUID.randomUUID()))).isFalse();
    }

    @Test
    void tokenBoundToASessionOfAnotherUserIsRejected() throws Exception {
        User other = SessionTestSupport.user(UserRole.technician);
        var foreign = sessions.service.create(other, "j", java.time.Instant.now().plusSeconds(3600));
        assertThat(authenticatedWith(jwtService.generateToken(user, foreign.getId()))).isFalse();
    }

    @Test
    void legacyTokenWithoutSessionStillWorksUntilItsOwnExpiry() throws Exception {
        assertThat(authenticatedWith(jwtService.generateToken(user))).isTrue();
    }

    @Test
    void deactivatedUserIsRejected() throws Exception {
        String token = jwtService.generateToken(user, session().getId());
        user.setActive(false);
        assertThat(authenticatedWith(token)).isFalse();
    }

    @Test
    void softDeletedUserIsRejected() throws Exception {
        String token = jwtService.generateToken(user, session().getId());
        user.setDeletedAt(java.time.Instant.now());
        assertThat(authenticatedWith(token)).isFalse();
    }

    @Test
    void tokenForAnAccountThatNoLongerExistsContinuesUnauthenticatedWithoutThrowing() throws Exception {
        String token = jwtService.generateToken(user);
        UserDetailsService gone = mock(UserDetailsService.class);
        when(gone.loadUserByUsername("tech1")).thenThrow(
            new org.springframework.security.core.userdetails.UsernameNotFoundException("x"));
        filter = new JwtAuthFilter(jwtService, gone, mock(TenantSubscriptionRepository.class),
            mock(SubscriptionCache.class), new ObjectMapper(), sessions.service);
        assertThat(authenticatedWith(token)).isFalse();
    }

    @Test
    void tokenIssuedBeforeTheInvalidationInstantIsRejectedAndALaterOneIsNot() throws Exception {
        String old = jwtService.generateToken(user);
        Thread.sleep(1100);
        user.setTokensValidAfter(java.time.Instant.now());
        assertThat(authenticatedWith(old)).isFalse();
        Thread.sleep(1100);
        assertThat(authenticatedWith(jwtService.generateToken(user))).isTrue();
    }

    @Test
    void sessionIdIsExposedToControllersAsARequestAttribute() throws Exception {
        var s = session();
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/users/me");
        req.addHeader("Authorization", "Bearer " + jwtService.generateToken(user, s.getId()));
        filter.doFilter(req, new MockHttpServletResponse(), mock(FilterChain.class));
        assertThat(req.getAttribute(JwtAuthFilter.REQ_ATTR_SID)).isEqualTo(s.getId());
    }

    // ── hard mode of the default-password policy ────────────────

    private int statusFor(String path, boolean hardMode, boolean mustChange) throws Exception {
        SecurityContextHolder.clearContext();
        ReflectionTestUtils.setField(filter, "enforcePasswordChange", hardMode);
        user.setMustChangePassword(mustChange);
        MockHttpServletRequest req = new MockHttpServletRequest("GET", path);
        req.setRequestURI(path);
        req.addHeader("Authorization", "Bearer " + jwtService.generateToken(user, session().getId()));
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilter(req, res, mock(FilterChain.class));
        return res.getStatus();
    }

    @Test
    void passwordChangeIsSoftByDefault() throws Exception {
        assertThat(statusFor("/api/work-orders", false, true)).isEqualTo(200);
    }

    @Test
    void hardModeBlocksEverythingButTheChangePasswordFlow() throws Exception {
        assertThat(statusFor("/api/work-orders", true, true)).isEqualTo(403);
        assertThat(statusFor("/api/users", true, true)).isEqualTo(403);
        assertThat(statusFor("/api/users/me", true, true)).isEqualTo(200);
        assertThat(statusFor("/api/users/me/change-password", true, true)).isEqualTo(200);
        assertThat(statusFor("/api/auth/logout", true, true)).isEqualTo(200);
    }

    @Test
    void hardModeDoesNotAffectAccountsWithoutTheFlag() throws Exception {
        assertThat(statusFor("/api/work-orders", true, false)).isEqualTo(200);
    }
}
