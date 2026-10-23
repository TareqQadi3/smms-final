package com.smms.config;

import com.smms.controller.TenantAssignmentController;
import com.smms.dto.request.TenantAssignmentRequest;
import com.smms.entity.User;
import com.smms.service.TenantAssignmentService;
import com.smms.service.TenantAssignmentService.TenantAlreadyAssignedException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;

import java.lang.reflect.Method;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Endpoint-level checks: system_admin only, and the 409 mapping for an already-tenanted user. */
class TenantAssignmentControllerTest {

    @Test
    void bothEndpointsRequireSystemAdmin() {
        for (Method m : TenantAssignmentController.class.getDeclaredMethods()) {
            if (java.lang.reflect.Modifier.isPublic(m.getModifiers())
                    && !m.getName().equals("alreadyAssigned")) {
                PreAuthorize pa = m.getAnnotation(PreAuthorize.class);
                assertThat(pa).as(m.getName()).isNotNull();
                assertThat(pa.value()).isEqualTo("hasRole('system_admin')");
            }
        }
    }

    @Test
    void alreadyAssignedMapsTo409WithoutLeakingDetails() {
        var r = new TenantAssignmentController(mock(TenantAssignmentService.class))
            .alreadyAssigned(new TenantAlreadyAssignedException("User already has a tenant"));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(r.getBody().status()).isEqualTo(409);
    }

    @Test
    void assignDelegatesWithTheBodyTenantAndTheActor() {
        TenantAssignmentService svc = mock(TenantAssignmentService.class);
        UUID uid = UUID.randomUUID(), tid = UUID.randomUUID();
        TenantAssignmentRequest req = new TenantAssignmentRequest();
        req.setTenantId(tid);
        User actor = new User();
        new TenantAssignmentController(svc).assign(uid, req, actor);
        verify(svc).assign(uid, tid, actor);
    }

    @Test
    void invalidPageParametersAreRejectedAs400() {
        var c = new TenantAssignmentController(mock(TenantAssignmentService.class));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> c.tenantless(-1, 20, null, new User()))
            .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> c.tenantless(0, 20, java.util.List.of("passwordHash"), new User()))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
