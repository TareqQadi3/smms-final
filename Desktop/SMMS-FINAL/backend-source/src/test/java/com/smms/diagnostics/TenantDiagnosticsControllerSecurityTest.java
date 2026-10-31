package com.smms.diagnostics;

import com.smms.util.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Runs the real @PreAuthorize expression through Spring method security. Anonymous callers never
 * reach the controller: the filter chain answers them 401 (entry point, owned by another change)
 * and, defensively, method security refuses an unauthenticated principal as well.
 */
class TenantDiagnosticsControllerSecurityTest {

    @Configuration
    @EnableMethodSecurity(proxyTargetClass = true)
    static class Config {}

    private AnnotationConfigApplicationContext ctx;
    private TenantDiagnosticsController controller;
    private TenantDiagnosticsService service;

    @BeforeEach
    void setUp() {
        service = mock(TenantDiagnosticsService.class);
        when(service.summary()).thenReturn(new TenantDiagnosticsService.Summary(Map.of(), Map.of(), 0,
            new TenantDiagnosticsService.FlywayState("10", 0, List.of())));
        when(service.mismatchPage(anyString(), anyInt(), anyInt())).thenReturn(
            new TenantDiagnosticsService.MismatchPage("users_center", 0, 50, 0, List.of()));
        ctx = new AnnotationConfigApplicationContext();
        ctx.register(Config.class);
        ctx.registerBean(TenantDiagnosticsController.class, () -> new TenantDiagnosticsController(service));
        ctx.refresh();
        controller = ctx.getBean(TenantDiagnosticsController.class);
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
    void platformAdminCanReadBothEndpoints() {
        login("system_admin");
        TenantContext.set(null);
        assertThat(controller.summary().getBody()).isNotNull();
        assertThat(controller.mismatches("users_center", 0, 50).getBody()).isNotNull();
    }

    @Test
    void tenantBoundSystemAdminIsDenied() {
        login("system_admin");
        TenantContext.set(UUID.randomUUID());
        assertThatThrownBy(controller::summary).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.mismatches("users_center", 0, 50)).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(service);
    }

    @Test
    void otherRolesAreDenied() {
        for (String role : List.of("hospital_supervisor", "region_supervisor", "maintenance_manager", "technician")) {
            SecurityContextHolder.clearContext();
            login(role);
            TenantContext.set(null);
            assertThatThrownBy(controller::summary).as(role).isInstanceOf(AccessDeniedException.class);
        }
        verifyNoInteractions(service);
    }

    @Test
    void anonymousIsRefusedByMethodSecurity() {
        SecurityContextHolder.clearContext();
        assertThatThrownBy(controller::summary).isInstanceOfAny(
            AccessDeniedException.class, org.springframework.security.authentication.AuthenticationCredentialsNotFoundException.class);
        verifyNoInteractions(service);
    }

    @Test
    void sizeIsClampedToTheHundredLimitAndBadPagingIs400() {
        login("system_admin");
        TenantContext.set(null);
        controller.mismatches("users_center", 0, 100000);
        verify(service).mismatchPage("users_center", 0, 100);
        assertThatThrownBy(() -> controller.mismatches("users_center", -1, 10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> controller.mismatches("users_center", 0, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void controllerExposesOnlyGetHandlers() {
        for (var m : TenantDiagnosticsController.class.getDeclaredMethods()) {
            assertThat(m.isAnnotationPresent(org.springframework.web.bind.annotation.PostMapping.class)).isFalse();
            assertThat(m.isAnnotationPresent(org.springframework.web.bind.annotation.PutMapping.class)).isFalse();
            assertThat(m.isAnnotationPresent(org.springframework.web.bind.annotation.DeleteMapping.class)).isFalse();
            assertThat(m.isAnnotationPresent(org.springframework.web.bind.annotation.PatchMapping.class)).isFalse();
        }
    }
}
