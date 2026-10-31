package com.smms.auth;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;

/** V11 and V12 are additive: they create objects and never rewrite or remove existing data. */
class AuthMigrationsTest {

    private static String sql(String name) throws IOException {
        try (InputStream in = AuthMigrationsTest.class.getResourceAsStream("/db/migration/" + name)) {
            assertThat(in).as(name).isNotNull();
            return new String(in.readAllBytes()).toLowerCase().replaceAll("[ \\t]+", " ");
        }
    }

    private static String code(String s) {
        return s.lines().filter(l -> !l.strip().startsWith("--")).collect(java.util.stream.Collectors.joining("\n"));
    }

    @Test
    void versionsElevenAndTwelveArePresent() throws IOException {
        assertThat(sql("V11__auth_sessions.sql")).isNotBlank();
        assertThat(sql("V12__upload_idempotency.sql")).isNotBlank();
    }

    @Test
    void neitherMigrationDropsDeletesUpdatesOrTruncates() throws IOException {
        for (String f : new String[] {"V11__auth_sessions.sql", "V12__upload_idempotency.sql"}) {
            String c = code(sql(f));
            assertThat(c).as(f).doesNotContainPattern("\\bdrop\\b").doesNotContainPattern("\\bdelete\\s+from\\b")
                .doesNotContain("truncate").doesNotContainPattern("\\bupdate\\s+\\w+\\s+set\\b")
                .doesNotContainPattern("\\binsert\\s+into\\b").doesNotContainPattern("\\brename\\b");
        }
    }

    @Test
    void sessionTableAndUserColumnsMatchTheEntities() throws IOException {
        String c = code(sql("V11__auth_sessions.sql"));
        assertThat(c).contains("create table if not exists auth_sessions")
            .contains("current_jti_hash varchar(64) not null")
            .contains("previous_jti_hash varchar(64)")
            .contains("references users(id) on delete cascade")
            .contains("revoke_reason varchar(40)")
            .contains("where revoked_at is null")
            .contains("must_change_password boolean not null default false")
            .contains("tokens_valid_after timestamptz");
        assertThat(c).doesNotContain("current_jti ").doesNotContain("refresh_token ");
    }

    @Test
    void idempotencyTableHasTheUniqueKeyAndNullableLinkColumn() throws IOException {
        String c = code(sql("V12__upload_idempotency.sql"));
        assertThat(c).contains("unique (user_id, idem_key)")
            .contains("idem_key varchar(64) not null")
            .contains("fingerprint varchar(64) not null")
            .contains("add column if not exists upload_request_id uuid references upload_requests(id) on delete set null");
    }

    @Test
    void migrationsAreIdempotentSoAHalfAppliedDatabaseCanBeRetried() throws IOException {
        for (String f : new String[] {"V11__auth_sessions.sql", "V12__upload_idempotency.sql"}) {
            String c = code(sql(f));
            assertThat(c).as(f).doesNotContainPattern("create table (?!if not exists)")
                .doesNotContainPattern("add column (?!if not exists)")
                .doesNotContainPattern("create index (?!if not exists)");
        }
    }
}
