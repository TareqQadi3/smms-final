package com.smms.procurement;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Validated report/list date range. Days are calendar days in Asia/Riyadh: {@code from} starts at
 * 00:00 of that day, {@code to} is inclusive (the range ends at 00:00 of the next day).
 */
public record DateRange(LocalDate from, LocalDate to) {

    public static final ZoneId ZONE = ZoneId.of("Asia/Riyadh");
    public static final int MAX_DAYS = 366;
    public static final int DEFAULT_DAYS = 30;

    public DateRange {
        if (from == null || to == null) throw new IllegalArgumentException("from and to are required");
        if (from.isAfter(to)) throw new IllegalArgumentException("from must be on or before to");
        if (ChronoUnit.DAYS.between(from, to) + 1 > MAX_DAYS) {
            throw new IllegalArgumentException("date range must not exceed " + MAX_DAYS + " days");
        }
    }

    /** Both or neither: neither gives the last 30 days ending today. One alone is a 400. */
    public static DateRange orDefault(LocalDate from, LocalDate to, LocalDate today) {
        if (from == null && to == null) return new DateRange(today.minusDays(DEFAULT_DAYS - 1L), today);
        return new DateRange(from, to);
    }

    public static DateRange orDefault(LocalDate from, LocalDate to) {
        return orDefault(from, to, LocalDate.now(ZONE));
    }

    /** Optional filter: null when neither bound is given. */
    public static DateRange optional(LocalDate from, LocalDate to) {
        return from == null && to == null ? null : new DateRange(from, to);
    }

    public Instant start() { return from.atStartOfDay(ZONE).toInstant(); }

    /** Exclusive end. */
    public Instant endExclusive() { return to.plusDays(1).atStartOfDay(ZONE).toInstant(); }
}
