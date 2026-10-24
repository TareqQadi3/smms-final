package com.smms.pm;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.ZoneId;

/**
 * Settings of the preventive maintenance job (all keys optional, safe defaults):
 * app.pm.enabled (true), app.pm.cron ("0 20 2 * * *", read by @Scheduled), app.pm.zone
 * ("Asia/Riyadh"), app.pm.batch-size (200 plans per pass), app.pm.overdue-batch-size (200).
 */
@Component
@ConfigurationProperties(prefix = "app.pm")
@Getter @Setter
public class PmProperties {
    private boolean enabled = true;
    private String cron = "0 20 2 * * *";
    private String zone = "Asia/Riyadh";
    private int batchSize = 200;
    private int overdueBatchSize = 200;

    public ZoneId zoneId() { return ZoneId.of(zone); }
}
