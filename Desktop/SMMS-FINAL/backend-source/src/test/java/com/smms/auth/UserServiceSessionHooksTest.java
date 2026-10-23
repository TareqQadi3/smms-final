package com.smms.auth;

import com.smms.entity.User;
import com.smms.enums.UserRole;
import com.smms.mapper.UserMapper;
import com.smms.repository.TenantRepository;
import com.smms.repository.UserControlRoomAccessRepository;
import com.smms.repository.UserRepository;
import com.smms.security.AdminScopePolicy;
import com.smms.security.TenantSecurityService;
import com.smms.service.PlanEnforcementService;
import com.smms.service.UserAccountValidator;
import com.smms.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Revocation hooks of UserService: deactivate, admin reset, self change. */
class UserServiceSessionHooksTest {

    private SessionTestSupport sessions;
    private UserRepository userRepo;
    private PasswordEncoder encoder;
    private TenantSecurityService tenantSecurity;
    private UserService service;
    private User target;
    private User admin;

    @BeforeEach
    void setUp() {
        sessions = new SessionTestSupport();
        userRepo = mock(UserRepository.class);
        encoder = mock(PasswordEncoder.class);
        tenantSecurity = mock(TenantSecurityService.class);
        service = new UserService(userRepo, mock(UserControlRoomAccessRepository.class), encoder,
            mock(UserMapper.class), mock(PlanEnforcementService.class), tenantSecurity,
            new AdminScopePolicy(mock(TenantRepository.class)), new UserAccountValidator());
        ReflectionTestUtils.setField(service, "authSessions", sessions.service);
        target = SessionTestSupport.user(UserRole.technician);
        admin = SessionTestSupport.user(UserRole.system_admin);
        when(tenantSecurity.requireUser(target.getId())).thenReturn(target);
        when(userRepo.save(any(User.class))).thenAnswer(i -> i.getArgument(0));
        when(encoder.encode(any())).thenReturn("newhash");
        when(encoder.matches("old-password", "hash")).thenReturn(true);
    }

    @Test
    void sessionServiceIsWiredByFieldInjection() throws Exception {
        assertThat(UserService.class.getDeclaredField("authSessions")
            .isAnnotationPresent(org.springframework.beans.factory.annotation.Autowired.class)).isTrue();
    }

    @Test
    void deactivationRevokesEverySessionAndCutsOffLegacyTokens() {
        var a = sessions.service.create(target, "a", Instant.now().plusSeconds(600));
        var b = sessions.service.create(target, "b", Instant.now().plusSeconds(600));
        service.toggleActive(target.getId(), false, admin);
        assertThat(target.isActive()).isFalse();
        assertThat(target.getTokensValidAfter()).isNotNull();
        assertThat(a.isRevoked()).isTrue();
        assertThat(b.isRevoked()).isTrue();
        assertThat(a.getRevokeReason()).isEqualTo(AuthSessionService.REASON_DEACTIVATED);
        verify(sessions.webSockets).closeSessions(target.getId(), null);
    }

    @Test
    void activationDoesNotRevokeAnything() {
        var a = sessions.service.create(target, "a", Instant.now().plusSeconds(600));
        service.toggleActive(target.getId(), true, admin);
        assertThat(a.isRevoked()).isFalse();
        assertThat(target.getTokensValidAfter()).isNull();
    }

    @Test
    void deniedDeactivationRevokesNothing() {
        when(tenantSecurity.requireUser(admin.getId())).thenReturn(admin);
        var a = sessions.service.create(admin, "a", Instant.now().plusSeconds(600));
        assertThatThrownBy(() -> service.toggleActive(admin.getId(), false, admin))
            .isInstanceOf(AccessDeniedException.class);
        assertThat(a.isRevoked()).isFalse();
        assertThat(admin.isActive()).isTrue();
    }

    @Test
    void adminResetRevokesSessionsFlagsTheAccountAndInvalidatesOldTokens() {
        var a = sessions.service.create(target, "a", Instant.now().plusSeconds(600));
        service.resetPassword(target.getId(), "Temp-Passw0rd!");
        assertThat(target.isMustChangePassword()).isTrue();
        assertThat(target.getTokensValidAfter()).isNotNull();
        assertThat(target.getPasswordHash()).isEqualTo("newhash");
        assertThat(a.isRevoked()).isTrue();
        assertThat(a.getRevokeReason()).isEqualTo(AuthSessionService.REASON_PASSWORD_RESET);
    }

    @Test
    void invalidResetPasswordChangesNothing() {
        var a = sessions.service.create(target, "a", Instant.now().plusSeconds(600));
        assertThatThrownBy(() -> service.resetPassword(target.getId(), "short"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(a.isRevoked()).isFalse();
        assertThat(target.isMustChangePassword()).isFalse();
    }

    @Test
    void selfChangeKeepsTheCurrentSessionRevokesTheOthersAndClearsTheFlag() {
        target.setMustChangePassword(true);
        var mine = sessions.service.create(target, "m", Instant.now().plusSeconds(600));
        var other = sessions.service.create(target, "o", Instant.now().plusSeconds(600));
        service.changePassword(target.getId(), "old-password", "Brand-new-pw1", mine.getId());
        assertThat(target.isMustChangePassword()).isFalse();
        assertThat(mine.isRevoked()).isFalse();
        assertThat(other.isRevoked()).isTrue();
        assertThat(other.getRevokeReason()).isEqualTo(AuthSessionService.REASON_PASSWORD_CHANGED);
        // the user's own current token must stay valid: no tokens_valid_after on a self change
        assertThat(target.getTokensValidAfter()).isNull();
    }

    @Test
    void selfChangeWithoutKnownSessionRevokesAll() {
        var a = sessions.service.create(target, "a", Instant.now().plusSeconds(600));
        service.changePassword(target.getId(), "old-password", "Brand-new-pw1");
        assertThat(a.isRevoked()).isTrue();
    }

    @Test
    void wrongCurrentPasswordRevokesNothingAndKeepsTheFlag() {
        target.setMustChangePassword(true);
        var a = sessions.service.create(target, "a", Instant.now().plusSeconds(600));
        assertThatThrownBy(() -> service.changePassword(target.getId(), "wrong", "Brand-new-pw1", UUID.randomUUID()))
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(a.isRevoked()).isFalse();
        assertThat(target.isMustChangePassword()).isTrue();
    }

    @Test
    void worksWithoutSessionServiceForHandBuiltServices() {
        ReflectionTestUtils.setField(service, "authSessions", null);
        service.toggleActive(target.getId(), false, admin);
        service.resetPassword(target.getId(), "Temp-Passw0rd!");
        assertThat(target.isMustChangePassword()).isTrue();
    }
}
