package com.smms.workflow;

import com.smms.entity.WorkOrder;

import java.time.Duration;
import java.time.Instant;

/**
 * SLA clock pause arithmetic (request for information). Pausing records {@code sla_paused_at};
 * resuming shifts the deadline by exactly the paused seconds (rounded to the nearest second) and adds them to
 * {@code sla_paused_seconds}. The pause is closed only by provide-info and by cancel.
 */
public final class SlaPause {
    private SlaPause() {}

    public static void pause(WorkOrder wo, Instant now) {
        if (wo.getSlaPausedAt() != null) throw new IllegalStateException("SLA already paused");
        wo.setSlaPausedAt(now);
    }

    /** Closes an open pause and returns the paused seconds (0 when nothing was paused). */
    public static long resume(WorkOrder wo, Instant now) {
        Instant since = wo.getSlaPausedAt();
        if (since == null) return 0L;
        // rounded to the nearest second: the stored pause start has microsecond precision, `now` nanoseconds
        long delta = Math.max(0L, (Duration.between(since, now).toMillis() + 500) / 1000);
        if (wo.getSlaDeadline() != null) wo.setSlaDeadline(wo.getSlaDeadline().plusSeconds(delta));
        wo.setSlaPausedSeconds(wo.getSlaPausedSeconds() + delta);
        wo.setSlaPausedAt(null);
        return delta;
    }

    /** Hours of the SLA window per priority; mirrors the set_sla_deadline() trigger of V1. */
    public static long slaHours(com.smms.enums.TicketPriority p) {
        if (p == null) return 48;
        return switch (p) { case critical -> 4; case high -> 8; case medium -> 24; case low -> 48; };
    }
}
