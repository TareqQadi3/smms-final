package com.smms.config;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class DefaultCredentialsReporterTest {

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4);

    @Test
    void countsOnlyAdministratorsWhoseHashMatchesThePublicDefault() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString())).thenReturn(List.of(
            Map.of("id", UUID.randomUUID(), "password_hash", encoder.encode(DefaultCredentialsReporter.PUBLIC_DEFAULT)),
            Map.of("id", UUID.randomUUID(), "password_hash", encoder.encode("some-private-password-123"))));
        assertThat(new DefaultCredentialsReporter(jdbc, encoder).report()).isEqualTo(1);
    }

    @Test
    void isReadOnlyAndNeverFailsStartup() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString())).thenThrow(new IllegalStateException("db down"));
        new DefaultCredentialsReporter(jdbc, encoder).onReady();
        verify(jdbc).queryForList(anyString());
        verifyNoMoreInteractions(jdbc); // no update/execute calls
    }
}
