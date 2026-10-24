package com.smms.procurement.support;

import org.flywaydb.core.api.Location;
import org.springframework.boot.autoconfigure.flyway.FlywayConfigurationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * TEST ONLY. Until the inventory block (V30-V34) is merged, V35-V39 depend on tables that do not
 * exist yet. This adds a stub migration location (version 29) to every Spring test context, but
 * only while no real {@code db/migration/V30__*.sql} is on the classpath: after the merge it switches
 * itself off and the stubs are never applied. Delete {@code fp1-w2b-stubs} and the two support
 * classes once W2a is merged.
 */
@Configuration
public class W2bTestSupportConfig {

    public static boolean realInventoryMigrationsPresent() {
        try {
            return new PathMatchingResourcePatternResolver().getResources("classpath*:db/migration/V30__*.sql").length > 0;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Bean
    FlywayConfigurationCustomizer w2bStubLocation() {
        return configuration -> {
            if (realInventoryMigrationsPresent()) return;
            List<Location> locations = new ArrayList<>(List.of(configuration.getLocations()));
            locations.add(new Location("classpath:fp1-w2b-stubs"));
            configuration.locations(locations.toArray(new Location[0]));
        };
    }
}
