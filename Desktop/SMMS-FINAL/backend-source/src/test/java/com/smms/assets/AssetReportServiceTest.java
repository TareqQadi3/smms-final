package com.smms.assets;

import com.smms.entity.User;
import com.smms.enums.UserRole;
import com.smms.reports.assets.AssetReportService;
import com.smms.support.Fixtures;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class AssetReportServiceTest {

    private final AssetReportService service = new AssetReportService(mock(JdbcTemplate.class));
    private final LocalDate d = LocalDate.parse("2025-03-01");

    @Test
    void rangeIsValidatedBeforeAnyQuery() {
        User mm = Fixtures.user(UserRole.maintenance_manager, null, null, UUID.randomUUID());
        assertThatThrownBy(() -> service.compliance(d, d.minusDays(1), null, mm)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.compliance(d, d.plusDays(367), null, mm)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.summary(null, d, null, mm)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rolesOutsideTheReportMatrixAreRefused() {
        for (UserRole r : new UserRole[] {UserRole.control_room, UserRole.technician, UserRole.warehouse,
            UserRole.resident, UserRole.department, UserRole.other}) {
            User u = Fixtures.user(r, null, null, UUID.randomUUID());
            assertThatThrownBy(() -> service.summary(d, d.plusDays(1), null, u)).as(r.name()).isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> service.compliance(d, d.plusDays(1), null, u)).as(r.name()).isInstanceOf(AccessDeniedException.class);
        }
    }
}
