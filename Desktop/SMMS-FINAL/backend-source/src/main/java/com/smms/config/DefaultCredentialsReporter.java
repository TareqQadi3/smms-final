package com.smms.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Read-only startup warning when an active system administrator still has the public default
 * password. It never writes, never blocks login and never fails startup; only ids are logged.
 * The login response already flags such accounts with passwordChangeRequired.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DefaultCredentialsReporter {

    /** The documented public default (same constant the login flag uses). */
    static final String PUBLIC_DEFAULT = "Admin@1234";

    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;

    /** After ALL runners (including DataInitializer) so a just-created default admin is reported too. */
    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        try {
            int n = report();
            if (n > 0) {
                log.warn("SECURITY: {} active system administrator account(s) still use the public default password; "
                    + "change it from Settings > Security (see docs/security/deployment-runbook.md)", n);
            }
        } catch (RuntimeException e) {
            log.warn("Default credential check skipped: {}", e.getClass().getSimpleName());
        }
    }

    /** Number of active system administrators whose hash matches the public default. */
    int report() {
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT id, password_hash FROM users WHERE role::text = 'system_admin' "
                + "AND is_active = TRUE AND deleted_at IS NULL");
        int matches = 0;
        for (Map<String, Object> row : rows) {
            String hash = String.valueOf(row.get("password_hash"));
            if (passwordEncoder.matches(PUBLIC_DEFAULT, hash)) {
                matches++;
                log.warn("SECURITY: default password in use for user id {}", row.get("id"));
            }
        }
        return matches;
    }
}
