package com.smms.workflow;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Public request-status feature. Keys: {@code app.public-status.enabled} (default FALSE: the
 * endpoint answers 404 and links cannot be created), {@code app.public-status.rate-limit-per-minute}
 * (30 per client address), {@code app.public-status.link-ttl-days} (30).
 */
@Component
@ConfigurationProperties(prefix = "app.public-status")
@Getter @Setter
public class PublicStatusProperties {
    private boolean enabled = false;
    private int rateLimitPerMinute = 30;
    private int linkTtlDays = 30;
}
