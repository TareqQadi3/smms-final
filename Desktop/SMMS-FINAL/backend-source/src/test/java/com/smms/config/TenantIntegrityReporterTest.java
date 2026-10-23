package com.smms.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import com.smms.diagnostics.TenantDiagnosticsService;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TenantIntegrityReporterTest {

    private final TenantDiagnosticsService diag = mock(TenantDiagnosticsService.class);

    private void verifyNoWrites(JdbcTemplate jdbc) {
        verify(jdbc, never()).update(anyString());
        verify(jdbc, never()).update(anyString(), any(Object[].class));
        verify(jdbc, never()).execute(anyString());
        verify(jdbc, never()).batchUpdate(any(String[].class));
    }

    @Test
    void reportOnlyReadsAndNeverWrites() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Long.class))).thenReturn(3L);
        when(jdbc.queryForList(anyString(), eq(String.class)))
            .thenReturn(List.of(UUID.randomUUID().toString()));

        new TenantIntegrityReporter(jdbc, diag).run(new DefaultApplicationArguments());

        verify(jdbc, atLeastOnce()).queryForObject(startsWith("SELECT count(*)"), eq(Long.class));
        verify(jdbc).queryForList(contains("LIMIT " + TenantIntegrityReporter.MAX_IDS), eq(String.class));
        verifyNoWrites(jdbc);
    }

    @Test
    void usersQueryExcludesPlatformAdminsAndDeletedRowsAndSelectsOnlyIds() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Long.class))).thenReturn(1L);
        when(jdbc.queryForList(anyString(), eq(String.class))).thenReturn(List.of());

        new TenantIntegrityReporter(jdbc, diag).run(new DefaultApplicationArguments());

        verify(jdbc).queryForList(argThat((String sql) -> sql.startsWith("SELECT id::text ")
            && sql.contains("role::text <> 'system_admin'") && sql.contains("deleted_at IS NULL")
            && !sql.toLowerCase().contains("email") && !sql.toLowerCase().contains("username")),
            eq(String.class));
    }

    @Test
    void missingTableOrDatabaseErrorNeverFailsStartup() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Long.class))).thenThrow(new RuntimeException("boom"));
        new TenantIntegrityReporter(jdbc, diag).run(new DefaultApplicationArguments());
        verifyNoWrites(jdbc);
    }

    @Test
    void disabledPropertySkipsEveryQuery() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        TenantIntegrityReporter r = new TenantIntegrityReporter(jdbc, diag);
        ReflectionTestUtils.setField(r, "enabled", false);
        r.run(new DefaultApplicationArguments());
        verifyNoInteractions(jdbc);
    }

    @Test
    void dataInitializerNoLongerHasAnyRawSqlOrBackfill() {
        for (var m : DataInitializer.class.getDeclaredMethods()) {
            org.assertj.core.api.Assertions.assertThat(m.getName())
                .doesNotContain("backfill").doesNotContain("migrateEnums");
        }
        org.assertj.core.api.Assertions.assertThat(
            java.util.Arrays.stream(DataInitializer.class.getDeclaredFields())
                .map(f -> f.getType().getName())).doesNotContain("javax.sql.DataSource");
    }

    @Test
    void mismatchesAreLoggedWithIdsOnlyCappedAtMaxIdsAndNeverFixed() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("work_orders_center", 2L);
        counts.put("users_center", 0L);
        when(diag.mismatchCounts()).thenReturn(counts);
        when(diag.mismatchIds(eq("work_orders_center"), eq(TenantIntegrityReporter.MAX_IDS)))
            .thenReturn(List.of(UUID.randomUUID().toString()));
        when(diag.flywayState()).thenReturn(new TenantDiagnosticsService.FlywayState("10", 0, List.of()));

        new TenantIntegrityReporter(jdbc, diag).run(new DefaultApplicationArguments());

        verify(diag).mismatchIds("work_orders_center", TenantIntegrityReporter.MAX_IDS);
        verify(diag, never()).mismatchIds(eq("users_center"), anyInt());
        verifyNoWrites(jdbc);
    }

    @Test
    void failingDiagnosticsNeverFailStartup() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(diag.mismatchCounts()).thenThrow(new RuntimeException("view missing"));
        when(diag.flywayState()).thenThrow(new RuntimeException("no history"));
        new TenantIntegrityReporter(jdbc, diag).run(new DefaultApplicationArguments());
        verifyNoWrites(jdbc);
    }

    @Test
    void failedMigrationRowIsReportedAsError() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(diag.mismatchCounts()).thenReturn(Map.of());
        when(diag.flywayState()).thenReturn(
            new TenantDiagnosticsService.FlywayState("8", 1, List.of("index uq_users_username_lower is missing")));
        new TenantIntegrityReporter(jdbc, diag).run(new DefaultApplicationArguments());
        verify(diag).flywayState();
    }
}
