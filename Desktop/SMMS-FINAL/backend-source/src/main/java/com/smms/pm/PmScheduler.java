package com.smms.pm;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/** Daily preventive maintenance job: generate due work orders, then notify overdue ones. */
@Component
@RequiredArgsConstructor
@Slf4j
public class PmScheduler {

    private final PmGenerationService generation;
    private final PmProperties props;

    @Scheduled(cron = "${app.pm.cron:0 20 2 * * *}", zone = "${app.pm.zone:Asia/Riyadh}")
    public void run() {
        if (!props.isEnabled()) return;
        try {
            LocalDate today = LocalDate.now(props.zoneId());
            generation.runDue(today);
            generation.notifyOverdue(today);
        } catch (RuntimeException e) {
            log.error("PM job failed: {}", e.getClass().getSimpleName(), e);
        }
    }
}
