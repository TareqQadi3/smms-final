package com.smms.notify;

import com.smms.controller.NotificationController;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

import static org.assertj.core.api.Assertions.assertThat;

/** Pinned authorization of the W4 endpoints (see also EndpointAuthorizationCoverageTest). */
class NotificationEndpointsSecurityTest {

    @Test
    void deliveryLogAndProviderStatusAreSystemAdminOnly() throws Exception {
        for (String m : new String[] {"deliveries", "providers"}) {
            var method = java.util.Arrays.stream(DeliveryAdminController.class.getDeclaredMethods())
                .filter(x -> x.getName().equals(m)).findFirst().orElseThrow();
            assertThat(method.getAnnotation(PreAuthorize.class).value()).isEqualTo("hasRole('system_admin')");
        }
    }

    @Test
    void preferencesAreForAuthenticatedCallersOnly() {
        for (var method : NotificationPreferencesController.class.getDeclaredMethods()) {
            if (method.getAnnotation(PreAuthorize.class) != null) {
                assertThat(method.getAnnotation(PreAuthorize.class).value()).isEqualTo("isAuthenticated()");
            }
        }
    }

    @Test
    void typeFilterValidation() {
        assertThat(NotificationPreferencesService.normalizeTypes(null)).isNull();
        assertThat(NotificationPreferencesService.normalizeTypes(java.util.List.of("NEW_TICKET", "NEW_TICKET", "ESCALATION")))
            .containsExactly("NEW_TICKET", "ESCALATION");
        for (java.util.List<String> bad : java.util.List.of(java.util.List.<String>of(), java.util.List.of("bad type"), java.util.List.of("x'; DROP")))
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> NotificationPreferencesService.normalizeTypes(bad))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(NotificationController.class.getAnnotation(org.springframework.web.bind.annotation.RequestMapping.class).value())
            .containsExactly("/api/notifications");
    }
}
