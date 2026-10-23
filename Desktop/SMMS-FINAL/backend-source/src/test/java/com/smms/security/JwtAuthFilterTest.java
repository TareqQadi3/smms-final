package com.smms.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smms.cache.SubscriptionCache;
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

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
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
            mock(SubscriptionCache.class), new ObjectMapper());
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
}
