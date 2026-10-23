package com.smms.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TenantIntegrityReporterTest {

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

        new TenantIntegrityReporter(jdbc).run(new DefaultApplicationArguments());

        verify(jdbc, atLeastOnce()).queryForObject(startsWith("SELECT count(*)"), eq(Long.class));
        verify(jdbc).queryForList(contains("LIMIT " + TenantIntegrityReporter.MAX_IDS), eq(String.class));
        verifyNoWrites(jdbc);
    }

    @Test
    void usersQueryExcludesPlatformAdminsAndDeletedRowsAndSelectsOnlyIds() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Long.class))).thenReturn(1L);
        when(jdbc.queryForList(anyString(), eq(String.class))).thenReturn(List.of());

        new TenantIntegrityReporter(jdbc).run(new DefaultApplicationArguments());

        verify(jdbc).queryForList(argThat((String sql) -> sql.startsWith("SELECT id::text ")
            && sql.contains("role::text <> 'system_admin'") && sql.contains("deleted_at IS NULL")
            && !sql.toLowerCase().contains("email") && !sql.toLowerCase().contains("username")),
            eq(String.class));
    }

    @Test
    void missingTableOrDatabaseErrorNeverFailsStartup() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Long.class))).thenThrow(new RuntimeException("boom"));
        new TenantIntegrityReporter(jdbc).run(new DefaultApplicationArguments());
        verifyNoWrites(jdbc);
    }

    @Test
    void disabledPropertySkipsEveryQuery() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        TenantIntegrityReporter r = new TenantIntegrityReporter(jdbc);
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
}
