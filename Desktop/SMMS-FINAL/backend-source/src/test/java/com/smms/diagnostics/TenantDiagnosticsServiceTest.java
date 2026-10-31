package com.smms.diagnostics;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TenantDiagnosticsServiceTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final TenantDiagnosticsService service = new TenantDiagnosticsService(jdbc);

    private void verifyNoWrites() {
        verify(jdbc, never()).update(anyString());
        verify(jdbc, never()).update(anyString(), any(Object[].class));
        verify(jdbc, never()).execute(anyString());
        verify(jdbc, never()).batchUpdate(any(String[].class));
    }

    @Test
    void unknownCheckIsRejectedBeforeAnyQuery() {
        assertThatThrownBy(() -> service.mismatchPage("work_orders_center; DROP TABLE users", 0, 10))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.mismatchIds(null, 5)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.mismatchPage("", 0, 10)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(jdbc);
    }

    @Test
    void knownCheckUsesBoundParametersAndStableOrder() {
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(1L);
        service.mismatchPage("work_orders_center", 2, 25);
        verify(jdbc).query(eq(TenantDiagnosticsService.SQL_MISMATCH_PAGE), any(RowMapper.class),
            eq("work_orders_center"), eq(25), eq(50L));
        assertThat(TenantDiagnosticsService.SQL_MISMATCH_PAGE).contains("ORDER BY check_name, row_id");
        verifyNoWrites();
    }

    @Test
    void everyFixedSqlIsASelectWithoutNames() {
        for (String sql : List.of(TenantDiagnosticsService.SQL_MISMATCH_COUNTS, TenantDiagnosticsService.SQL_NULL_COUNTS,
                TenantDiagnosticsService.SQL_MISMATCH_IDS, TenantDiagnosticsService.SQL_MISMATCH_PAGE,
                TenantDiagnosticsService.SQL_MISMATCH_TOTAL, TenantDiagnosticsService.SQL_FLYWAY_CURRENT,
                TenantDiagnosticsService.SQL_FLYWAY_FAILED, TenantDiagnosticsService.SQL_UNVALIDATED,
                TenantDiagnosticsService.SQL_NOT_NULL_MISSING, TenantDiagnosticsService.SQL_MISSING_CONSTRAINTS, TenantDiagnosticsService.SQL_USERNAME_INDEX)) {
            String l = sql.toLowerCase();
            assertThat(l).startsWith("select ");
            assertThat(l).doesNotContain("update ").doesNotContain("insert ").doesNotContain("delete ")
                .doesNotContain("drop ").doesNotContain("email").doesNotContain("username,").doesNotContain("full_name");
        }
    }

    @Test
    void mismatchCountsIncludeEveryCheckWithZeros() {
        var counts = service.mismatchCounts();
        assertThat(counts.keySet()).containsExactlyElementsOf(TenantDiagnosticsService.CHECKS);
        assertThat(counts.values()).containsOnly(0L);
    }

    @Test
    void idsAreReturnedAsStringsOnly() {
        String id = UUID.randomUUID().toString();
        when(jdbc.queryForList(eq(TenantDiagnosticsService.SQL_MISMATCH_IDS), eq(String.class), eq("users_center"), eq(50)))
            .thenReturn(List.of(id));
        assertThat(service.mismatchIds("users_center", 50)).containsExactly(id);
    }

    @Test
    void flywayStateSurvivesAMissingHistoryTable() {
        when(jdbc.queryForList(eq(TenantDiagnosticsService.SQL_FLYWAY_CURRENT), eq(String.class)))
            .thenThrow(new RuntimeException("no table"));
        var st = service.flywayState();
        assertThat(st.currentVersion()).isNull();
        assertThat(st.failedMigrations()).isZero();
    }

    @Test
    void whitelistCoversTheStructuralAndParentTenantlessChecks() {
        assertThat(TenantDiagnosticsService.CHECKS).contains("work_orders_center_vs_room_center",
            "user_room_access_user_vs_room", "system_admin_with_tenant", "work_orders_center_parent_tenantless",
            "ticket_history_work_order", "attachments_work_order", "notifications_user");
    }
}
