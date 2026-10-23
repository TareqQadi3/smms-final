package com.smms.service;

import com.smms.dto.request.LoginRequest;
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
    private PasswordEncoder encoder;
    private BruteForceProtectionService bruteForce;
    private AuthService service;
    private User user;
    private LoginRequest req;

    @BeforeEach
    void setUp() {
        userRepo = mock(UserRepository.class);
        jwtService = mock(JwtService.class);
        encoder = mock(PasswordEncoder.class);
        bruteForce = mock(BruteForceProtectionService.class);
        service = new AuthService(userRepo, mock(TenantRepository.class), jwtService, encoder, bruteForce);

        user = new User();
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
        when(jwtService.generateToken(user)).thenReturn("a");
        when(jwtService.generateRefreshToken(user)).thenReturn("r");
        assertThat(service.login(req).getToken()).isEqualTo("a");
        verify(bruteForce).recordSuccess("tech1");
        verify(bruteForce, never()).recordFailure(any());
    }

    @Test
    void refreshRejectsNonRefreshToken() {
        when(jwtService.isRefreshToken("access")).thenReturn(false);
        assertThatThrownBy(() -> service.refresh("access")).isInstanceOf(AuthException.class);
        verify(jwtService, never()).generateToken(any());
    }

    @Test
    void nonAdminWithoutTenantCannotLogin() {
        user.setTenantId(null);
        when(encoder.matches("pw", "hash")).thenReturn(true);
        assertThatThrownBy(() -> service.login(req)).isInstanceOf(AuthException.class)
            .hasMessage("Invalid credentials");
        verify(jwtService, never()).generateToken(any());
        verify(jwtService, never()).generateRefreshToken(any());
    }

    @Test
    void systemAdminWithoutTenantCanLogin() {
        user.setRole(UserRole.system_admin);
        user.setTenantId(null);
        when(encoder.matches("pw", "hash")).thenReturn(true);
        when(jwtService.generateToken(user)).thenReturn("a");
        assertThat(service.login(req).getToken()).isEqualTo("a");
    }

    @Test
    void refreshIsRefusedForNonAdminWithoutTenant() {
        user.setTenantId(null);
        when(jwtService.isRefreshToken("r")).thenReturn(true);
        when(jwtService.extractUsername("r")).thenReturn("tech1");
        when(jwtService.isTokenValid("r", user)).thenReturn(true);
        assertThatThrownBy(() -> service.refresh("r")).isInstanceOf(AuthException.class);
        verify(jwtService, never()).generateToken(any());
    }
}
