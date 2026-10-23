package com.smms.auth;

import com.smms.config.DataInitializer;
import com.smms.entity.Tenant;
import com.smms.entity.User;
import com.smms.enums.UserRole;
import com.smms.repository.MaintenanceTypeRepository;
import com.smms.repository.TenantRepository;
import com.smms.repository.TenantSubscriptionRepository;
import com.smms.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** Default admin: never resurrected, never touched when present, soft "change required" flag. */
class DataInitializerAdminTest {

    private UserRepository userRepo;
    private PasswordEncoder encoder;
    private JdbcTemplate jdbc;
    private DataInitializer init;

    @BeforeEach
    void setUp() {
        userRepo = mock(UserRepository.class);
        encoder = mock(PasswordEncoder.class);
        jdbc = mock(JdbcTemplate.class);
        TenantRepository tenantRepo = mock(TenantRepository.class);
        Tenant t = new Tenant();
        t.setId(UUID.randomUUID());
        when(tenantRepo.findBySlug("default")).thenReturn(Optional.of(t));
        TenantSubscriptionRepository subs = mock(TenantSubscriptionRepository.class);
        when(subs.existsByTenantId(any())).thenReturn(true);
        MaintenanceTypeRepository types = mock(MaintenanceTypeRepository.class);
        when(types.count()).thenReturn(1L);
        when(encoder.encode(anyString())).thenAnswer(i -> "enc:" + i.getArgument(0));
        init = new DataInitializer(userRepo, encoder, types, tenantRepo, subs, jdbc);
        ReflectionTestUtils.setField(init, "initialAdminPassword", "");
    }

    private void adminRowsInDb(int n) {
        when(jdbc.queryForObject(anyString(), eq(Integer.class))).thenReturn(n);
    }

    @Test
    void freshInstallCreatesAdminWithLegacyDefaultAndTheChangeFlag() {
        adminRowsInDb(0);
        init.run();
        var cap = org.mockito.ArgumentCaptor.forClass(User.class);
        verify(userRepo).save(cap.capture());
        User admin = cap.getValue();
        assertThat(admin.getUsername()).isEqualTo("admin");
        assertThat(admin.getRole()).isEqualTo(UserRole.system_admin);
        assertThat(admin.getTenantId()).isNull();
        assertThat(admin.getPasswordHash()).isEqualTo("enc:Admin@1234");
        assertThat(admin.isMustChangePassword()).isTrue();
    }

    @Test
    void configuredInitialPasswordIsUsedAndNotFlagged() {
        adminRowsInDb(0);
        ReflectionTestUtils.setField(init, "initialAdminPassword", "A-long-random-initial-pw-1");
        init.run();
        var cap = org.mockito.ArgumentCaptor.forClass(User.class);
        verify(userRepo).save(cap.capture());
        assertThat(cap.getValue().getPasswordHash()).isEqualTo("enc:A-long-random-initial-pw-1");
        assertThat(cap.getValue().isMustChangePassword()).isFalse();
    }

    @Test
    void tooShortOrTooLongConfiguredPasswordCreatesNoAdminAtAll() {
        adminRowsInDb(0);
        ReflectionTestUtils.setField(init, "initialAdminPassword", "short-11-ch");
        init.run();
        ReflectionTestUtils.setField(init, "initialAdminPassword", "x".repeat(129));
        init.run();
        verify(userRepo, never()).save(any(User.class));
        verify(encoder, never()).encode(anyString());
    }

    @Test
    void anExistingAdminIsNeverTouched() {
        adminRowsInDb(1);
        init.run();
        verify(userRepo, never()).save(any(User.class));
        verifyNoInteractions(encoder);
    }

    @Test
    void aSoftDeletedAdminIsNotResurrectedWithADefaultPassword() {
        // the existence check counts rows whatever their deleted_at
        adminRowsInDb(1);
        init.run();
        verify(userRepo, never()).save(any(User.class));
        verify(userRepo, never()).existsByUsernameAndDeletedAtIsNull("admin");
        var sql = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(jdbc).queryForObject(sql.capture(), eq(Integer.class));
        assertThat(sql.getValue().toLowerCase()).contains("from users").contains("lower(username)")
            .doesNotContain("deleted_at");
    }
}
