package com.smms.pm;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * Date maths of preventive maintenance plans. Pure LocalDate arithmetic (no time zone or DST
 * involved). The series is anchored on the start date: occurrence n is {@code start + n*interval},
 * so month-end clamping never drifts (Jan 31, Feb 28, Mar 31, ...).
 */
public final class PmSchedule {

    private PmSchedule() {}

    /** First occurrence of the series strictly after {@code current}. */
    public static LocalDate nextAfter(LocalDate anchor, FrequencyUnit unit, int interval, LocalDate current) {
        if (interval < 1) throw new IllegalArgumentException("interval must be >= 1");
        long n = 0;
        if (current.isAfter(anchor)) {
            ChronoUnit cu = switch (unit) {
                case day -> ChronoUnit.DAYS; case week -> ChronoUnit.WEEKS;
                case month -> ChronoUnit.MONTHS; case year -> ChronoUnit.YEARS;
            };
            n = Math.max(0, cu.between(anchor, current) / interval - 1);
        }
        while (!unit.plus(anchor, n, interval).isAfter(current)) n++;
        return unit.plus(anchor, n, interval);
    }

    /** First occurrence on or after {@code day} (used when a paused plan is re-activated). */
    public static LocalDate firstOnOrAfter(LocalDate anchor, FrequencyUnit unit, int interval, LocalDate day) {
        if (!anchor.isBefore(day)) return anchor;
        LocalDate c = nextAfter(anchor, unit, interval, day.minusDays(1));
        return c;
    }

    /** A plan is due for generation once {@code today >= nextDue - leadDays}. */
    public static boolean isDue(LocalDate nextDue, int leadDays, LocalDate today) {
        return !nextDue.minusDays(leadDays).isAfter(today);
    }
}
