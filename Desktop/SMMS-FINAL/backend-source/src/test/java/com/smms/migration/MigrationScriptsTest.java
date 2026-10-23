package com.smms.migration;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * V1/V2 under db/migration are copies of scripts/init.sql and scripts/V2__schema_migration.sql
 * (the Docker build context cannot reach ../scripts). They must never drift: Flyway checksums
 * them, and the entrypoint runs the originals.
 */
class MigrationScriptsTest {

    private static final Path SCRIPTS = Path.of("..", "scripts");

    private static String sha256(byte[] b) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
    }

    private static byte[] resource(String name) throws IOException {
        try (InputStream in = MigrationScriptsTest.class.getResourceAsStream("/db/migration/" + name)) {
            assertThat(in).as("classpath db/migration/" + name).isNotNull();
            return in.readAllBytes();
        }
    }

    @Test
    void baselineCopiesAreByteIdenticalToScripts() throws Exception {
        Path init = SCRIPTS.resolve("init.sql");
        Path v2 = SCRIPTS.resolve("V2__schema_migration.sql");
        assumeTrue(Files.exists(init) && Files.exists(v2), "../scripts not available (e.g. Docker build)");
        assertThat(sha256(resource("V1__init.sql"))).isEqualTo(sha256(Files.readAllBytes(init)));
        assertThat(sha256(resource("V2__schema_migration.sql"))).isEqualTo(sha256(Files.readAllBytes(v2)));
    }

    @Test
    void versionsAreContiguousAndPresent() {
        for (String f : List.of("V1__init.sql", "V2__schema_migration.sql", "V3__user_role_other.sql",
                "V4__tenant_backfill_parents.sql", "V5__child_tenant_backfill.sql",
                "V6__child_tenant_constraints.sql", "V7__default_tenant_seed_hierarchy.sql",
                "V8__username_case_insensitive_unique.sql")) {
            assertThat(MigrationScriptsTest.class.getResource("/db/migration/" + f)).as(f).isNotNull();
        }
    }

    @Test
    void tenantBackfillNeverUsesADefaultTenant() throws Exception {
        for (String f : List.of("V4__tenant_backfill_parents.sql", "V5__child_tenant_backfill.sql")) {
            String sql = new String(resource(f)).toLowerCase();
            assertThat(sql).as(f).doesNotContain("default_tenant").doesNotContain("limit 1");
            // every tenant_id assignment must come from a parent join (or be the system_admin clear)
            assertThat(sql).as(f).doesNotContain("set tenant_id = '");
        }
    }

    @Test
    void migrationsAreTransactionalAndNeverDestructive() throws Exception {
        for (String f : List.of("V3__user_role_other.sql", "V4__tenant_backfill_parents.sql",
                "V5__child_tenant_backfill.sql", "V6__child_tenant_constraints.sql",
                "V7__default_tenant_seed_hierarchy.sql", "V8__username_case_insensitive_unique.sql")) {
            String sql = new String(resource(f)).toLowerCase();
            assertThat(sql).as(f).doesNotContain("drop table").doesNotContain("truncate")
                .doesNotContain("delete from").doesNotContain("concurrently");
        }
    }

    @Test
    void seedTenantMigrationOnlyTouchesFixedSeedIdsWhereTenantIsNull() throws Exception {
        String sql = new String(resource("V7__default_tenant_seed_hierarchy.sql")).toLowerCase();
        assertThat(sql).contains("11111111-1111-1111-1111-111111111111")
            .contains("22222222-2222-2222-2222-222222222222");
        for (String stmt : sql.split(";")) {
            if (stmt.contains("update ")) {
                assertThat(stmt).contains("tenant_id is null");
                assertThat(stmt.contains("'11111111-1111") || stmt.contains("'22222222-2222")).isTrue();
            }
        }
    }
}
