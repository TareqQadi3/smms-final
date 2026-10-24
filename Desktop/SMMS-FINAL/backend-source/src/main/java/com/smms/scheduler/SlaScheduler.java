package com.smms.scheduler;

import com.smms.workflow.EscalationService;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodic SLA check. Marks overdue tickets as breached and raises escalation levels through
 * {@link EscalationService} (history rows, thresholds, duplicate prevention by a unique
 * constraint). The delay is {@code app.escalation.check-ms} (default 5 minutes).
 */
@Component
@RequiredArgsConstructor
public class SlaScheduler {

    private final EscalationService escalationService;

    @Scheduled(fixedDelayString = "${app.escalation.check-ms:300000}")
    public void checkBreaches() {
        escalationService.runScheduledPass();
    }
}
