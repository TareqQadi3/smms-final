package com.smms.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/** The feature-program-1 keys exist in the shipped settings, with safe values and no secrets. */
class Fp1SettingsFilesTest {

    private static Properties load(String name) {
        YamlPropertiesFactoryBean f = new YamlPropertiesFactoryBean();
        f.setResources(new ClassPathResource(name));
        return f.getObject();
    }

    private static final List<String> COMMON = List.of(
        "app.pm.enabled", "app.pm.cron", "app.pm.zone", "app.pm.batch-size", "app.pm.overdue-batch-size",
        "app.inventory.lock-timeout-ms", "app.inventory.zone", "app.inventory.max-range-days",
        "app.inventory.default-range-days", "app.inventory.low-stock-notifications",
        "app.notifications.scheduler-enabled", "app.notifications.dispatch-ms", "app.notifications.enqueue-ms",
        "app.notifications.batch-size", "app.notifications.max-attempts", "app.notifications.lease-seconds",
        "app.notifications.lookback-hours",
        "app.escalation.enabled", "app.escalation.check-ms", "app.escalation.notify-priorities", "app.escalation.level2-factor");

    @Test
    void commonKeysAreInApplicationYml() {
        Properties p = load("application.yml");
        for (String k : COMMON) assertThat(p.containsKey(k)).as(k).isTrue();
    }

    @Test
    void publicStatusIsOnlyEnabledInDev() {
        assertThat(load("application-dev.yml").getProperty("app.public-status.enabled")).isEqualTo("true");
        assertThat(load("application-prod.yml").getProperty("app.public-status.enabled")).isEqualTo("${PUBLIC_STATUS_ENABLED:false}");
        assertThat(load("application.yml").containsKey("app.public-status.enabled")).isFalse();
    }

    @Test
    void externalChannelsAreOffAndSecretsAreEnvPlaceholders() {
        for (String f : List.of("application-dev.yml", "application-prod.yml")) {
            Properties p = load(f);
            assertThat(String.valueOf(p.getProperty("app.notifications.sms.enabled"))).as(f).matches("false|\\$\\{NOTIFY_SMS_ENABLED:false}");
            assertThat(String.valueOf(p.getProperty("app.notifications.email.enabled"))).as(f).matches("false|\\$\\{NOTIFY_EMAIL_ENABLED:false}");
            assertThat(p.getProperty("app.notifications.sms.token")).as(f).isEqualTo("${SMS_TOKEN:}");
            assertThat(p.containsKey("app.procurement.manager-approval-limit")).as(f).isTrue();
        }
    }

    @Test
    void envExampleListsTheNewVariablesWithoutValuesForSecrets() throws Exception {
        Path env = Path.of("..", ".env.example");
        if (!Files.exists(env)) return; // building outside the repository layout
        String s = Files.readString(env);
        for (String k : List.of("PUBLIC_STATUS_ENABLED=false", "PROCUREMENT_MANAGER_APPROVAL_LIMIT=", "NOTIFY_SMS_ENABLED=false", "SMS_TOKEN=\n")) {
            assertThat(s).contains(k);
        }
    }
}
