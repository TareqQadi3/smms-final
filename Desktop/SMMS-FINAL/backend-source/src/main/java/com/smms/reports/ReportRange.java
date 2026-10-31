package com.smms.reports;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * Validated report period: calendar days in Asia/Riyadh, both ends inclusive. Rules: from and to
 * are given together or both omitted (default: the last 30 days ending today), from &lt;= to, at
 * most {@link #MAX_DAYS} days, no date before 2000. Violations are 400 (IllegalArgumentException).
 */
public record ReportRange(LocalDate from, LocalDate to) {

    public static final ZoneId ZONE = ZoneId.of("Asia/Riyadh");
    public static final int MAX_DAYS = 366;
    public static final int DEFAULT_DAYS = 30;
    private static final LocalDate EARLIEST = LocalDate.of(2000, 1, 1);

    public ReportRange {
        if (from == null || to == null) throw new IllegalArgumentException("from and to are required");
        if (from.isAfter(to)) throw new IllegalArgumentException("from must not be after to");
        if (from.isBefore(EARLIEST)) throw new IllegalArgumentException("from is too early");
        if (days(from, to) > MAX_DAYS) {
            throw new IllegalArgumentException("The date range may span at most " + MAX_DAYS + " days");
        }
    }

    public static ReportRange parse(LocalDate from, LocalDate to, Clock clock) {
        if (from == null && to == null) {
            LocalDate today = LocalDate.now(clock.withZone(ZONE));
            return new ReportRange(today.minusDays(DEFAULT_DAYS - 1L), today);
        }
        if (from == null || to == null) throw new IllegalArgumentException("from and to must be given together");
        return new ReportRange(from, to);
    }

    private static long days(LocalDate from, LocalDate to) {
        return java.time.temporal.ChronoUnit.DAYS.between(from, to) + 1;
    }

    /** Inclusive start instant. */
    public Instant start() { return from.atStartOfDay(ZONE).toInstant(); }

    /** Exclusive end instant (start of the day after {@code to}). */
    public Instant endExclusive() { return to.plusDays(1).atStartOfDay(ZONE).toInstant(); }

    public String fileSuffix() { return from + "-" + to; }
}
