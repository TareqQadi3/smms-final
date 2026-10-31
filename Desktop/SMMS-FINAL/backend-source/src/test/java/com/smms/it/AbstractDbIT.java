package com.smms.it;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.Base64;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Base class of database-backed integration tests. Tagged {@code db}: {@code mvn test} skips them
 * (surefire excludes the group), {@code mvn -Pit test} runs them. Without {@code SMMS_IT_DB_URL} the
 * class is skipped, never run against a default database. Flyway migrates the target database from
 * scratch (or from whatever state the throwaway database has) and Hibernate validates the mapping
 * against it ({@code ddl-auto: validate}) when the context starts. See docs/db-integration-tests.md.
 */
@Tag("db")
@SpringBootTest
public abstract class AbstractDbIT {

    @BeforeAll
    static void requireDatabase() {
        assumeTrue(DbItSupport.configured(), DbItSupport.ENV_URL + " is not set: database tests skipped");
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        if (!DbItSupport.configured()) return; // class is skipped by the assumption above
        DbItSupport.Target t = DbItSupport.target();
        registry.add("spring.datasource.url", t::url);
        registry.add("spring.datasource.username", t::user);
        registry.add("spring.datasource.password", t::password);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        // random per run, never a real secret
        registry.add("app.jwt.secret", () -> Base64.getEncoder().encodeToString(
            java.util.UUID.randomUUID().toString().concat(java.util.UUID.randomUUID().toString()).getBytes()));
        // no seeded admin password is needed; the seed account is not used by these tests
        registry.add("spring.main.banner-mode", () -> "off");
    }
}
