package com.smms.controller;

import com.smms.service.PlanEnforcementService;
import com.smms.service.SubscriptionService;
import com.smms.service.TenantService;
import com.smms.util.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * M3-C: /api/platform/tenants acts across all tenants, so a tenant-bound system_admin (tenant in the
 * token) must be refused. Runs the real @PreAuthorize expression through Spring method security.
 */
class TenantControllerMethodSecurityTest {

    @Configuration
    @EnableMethodSecurity(proxyTargetClass = true)
    static class Config {}

    private AnnotationConfigApplicationContext ctx;
    private TenantController controller;

    @BeforeEach
    void setUp() {
        TenantService tenantService = mock(TenantService.class);
        when(tenantService.list(any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));
        ctx = new AnnotationConfigApplicationContext();
        ctx.register(Config.class);
        ctx.registerBean(TenantController.class, () -> new TenantController(
            tenantService, mock(SubscriptionService.class), mock(PlanEnforcementService.class)));
        ctx.refresh();
        controller = ctx.getBean(TenantController.class);
    }

    @AfterEach
    void clear() {
        ctx.close();
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }

    private static void login(String role) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
            "u", "n/a", List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }

    @Test
    void platformAdminIsAllowed() {
        login("system_admin");
        TenantContext.set(null);
        assertThat(controller.listAll(0, 10, null).getBody()).isNotNull();
    }

    @Test
    void tenantBoundSystemAdminIsDenied() {
        login("system_admin");
        TenantContext.set(UUID.randomUUID());
        assertThatThrownBy(() -> controller.listAll(0, 10, null)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.suspend(UUID.randomUUID(), "x")).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.allUsage()).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void otherRolesAreDeniedEvenWithoutTenant() {
        login("hospital_supervisor");
        TenantContext.set(null);
        assertThatThrownBy(() -> controller.listAll(0, 10, null)).isInstanceOf(AccessDeniedException.class);
    }
}
