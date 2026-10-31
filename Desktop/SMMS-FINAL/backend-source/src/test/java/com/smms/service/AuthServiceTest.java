package com.smms.service;

import com.smms.dto.request.LoginRequest;
import com.smms.auth.AuthSessionService;
import com.smms.auth.SessionTestSupport;
import com.smms.entity.User;
import com.smms.enums.UserRole;
import com.smms.exception.AccountLockedException;
import com.smms.exception.AuthException;
import com.smms.repository.TenantRepository;
import com.smms.repository.UserRepository;
import com.smms.security.BruteForceProtectionService;
import com.smms.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AuthServiceTest {

    private UserRepository userRepo;
    private JwtService jwtService;
    private SessionTestSupport sessions;
    private PasswordEncoder encoder;
    private BruteForceProtectionService bruteForce;
    private AuthService service;
    private User user;
    private LoginRequest req;

    @BeforeEach
    void setUp() {
        userRepo = mock(UserRepository.class);
        jwtService = SessionTestSupport.jwt();
        sessions = new SessionTestSupport();
        encoder = mock(PasswordEncoder.class);
        bruteForce = mock(BruteForceProtectionService.class);
        service = new AuthService(userRepo, mock(TenantRepository.class), jwtService, encoder, bruteForce, sessions.service);

        user = new User();
        user.setId(java.util.UUID.randomUUID());
        user.setUsername("tech1");
        user.setPasswordHash("hash");
        user.setRole(UserRole.technician);
        user.setTenantId(java.util.UUID.randomUUID());
        user.setActive(true);
        when(userRepo.findByUsernameAndDeletedAtIsNull("tech1")).thenReturn(Optional.of(user));

        req = new LoginRequest();
        req.setUsername("tech1");
        req.setPassword("pw");
    }

    @Test
    void lockedAccountIsRejectedBeforeAuthenticating() {
        when(bruteForce.isBlocked("tech1")).thenReturn(true);
        assertThatThrownBy(() -> service.login(req)).isInstanceOf(AccountLockedException.class);
        verifyNoInteractions(encoder);
        verify(userRepo, never()).findByUsernameAndDeletedAtIsNull(any());
    }

    @Test
    void badPasswordRecordsFailure() {
        when(encoder.matches("pw", "hash")).thenReturn(false);
        assertThatThrownBy(() -> service.login(req)).isInstanceOf(AuthException.class);
        verify(bruteForce).recordFailure("tech1");
        verify(bruteForce, never()).recordSuccess(any());
    }

    @Test
    void unknownUserRecordsFailure() {
        req.setUsername("ghost");
        when(userRepo.findByUsernameAndDeletedAtIsNull("ghost")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.login(req)).isInstanceOf(AuthException.class);
        verify(bruteForce).recordFailure("ghost");
    }

    @Test
    void successResetsCounter() {
        when(encoder.matches("pw", "hash")).thenReturn(true);
        assertThat(service.login(req).getToken()).isNotBlank();
        verify(bruteForce).recordSuccess("tech1");
        verify(bruteForce, never()).recordFailure(any());
    }

    @Test
    void refreshRejectsNonRefreshToken() {
        String access = jwtService.generateToken(user);
        assertThatThrownBy(() -> service.refresh(access)).isInstanceOf(AuthException.class);
        assertThat(sessions.store).isEmpty();
    }

    @Test
    void nonAdminWithoutTenantCannotLogin() {
        user.setTenantId(null);
        when(encoder.matches("pw", "hash")).thenReturn(true);
        assertThatThrownBy(() -> service.login(req)).isInstanceOf(AuthException.class)
            .hasMessage("Invalid credentials");
        assertThat(sessions.store).isEmpty(); // no session, so no token was issued
    }

    @Test
    void systemAdminWithoutTenantCanLogin() {
        user.setRole(UserRole.system_admin);
        user.setTenantId(null);
        when(encoder.matches("pw", "hash")).thenReturn(true);
        assertThat(service.login(req).getToken()).isNotBlank();
    }

    @Test
    void refreshIsRefusedForNonAdminWithoutTenant() {
        when(encoder.matches("pw", "hash")).thenReturn(true);
        String refresh = service.login(req).getRefreshToken();
        user.setTenantId(null);
        assertThatThrownBy(() -> service.refresh(refresh)).isInstanceOf(AuthException.class);
    }

    @Test
    void tenantlessUserWithWrongPasswordGetsTheSameErrorAndCountsAsFailure() {
        user.setTenantId(null);
        when(encoder.matches("pw", "hash")).thenReturn(false);
        assertThatThrownBy(() -> service.login(req)).isInstanceOf(AuthException.class)
            .hasMessage("Invalid credentials");
        verify(bruteForce).recordFailure("tech1");
    }

    @Test
    void userCanLoginOnceATenantHasBeenAssigned() {
        user.setTenantId(null);
        when(encoder.matches("pw", "hash")).thenReturn(true);
        assertThatThrownBy(() -> service.login(req)).isInstanceOf(AuthException.class);
        user.setTenantId(java.util.UUID.randomUUID()); // admin assigned a tenant
        assertThat(service.login(req).getToken()).isNotBlank();
    }

    @Test
    void inactiveAccountIsRefusedBeforeAnyTokenIsIssued() {
        user.setActive(false);
        assertThatThrownBy(() -> service.login(req)).isInstanceOf(AuthException.class);
        assertThat(sessions.store).isEmpty();
    }
}
