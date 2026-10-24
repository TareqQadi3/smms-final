package com.smms.workflow;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Settings of the automatic SLA escalation (safe defaults in code; override in application*.yml).
 * Keys: {@code app.escalation.enabled} (true), {@code app.escalation.check-ms} (300000),
 * {@code app.escalation.notify-priorities} (critical,high),
 * {@code app.escalation.level2-factor} (0.5: level 2 when now &gt; deadline + factor * SLA hours).
 */
@Component
@ConfigurationProperties(prefix = "app.escalation")
@Getter @Setter
public class EscalationProperties {
    private boolean enabled = true;
    private long checkMs = 300_000;
    private List<String> notifyPriorities = new ArrayList<>(List.of("critical", "high"));
    private double level2Factor = 0.5;

    public boolean notifies(com.smms.enums.TicketPriority p) {
        return p != null && notifyPriorities.stream().anyMatch(s -> s != null && s.trim().equalsIgnoreCase(p.name()));
    }
}
