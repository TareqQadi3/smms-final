package com.smms.pm;

import java.time.LocalDate;

public enum FrequencyUnit {
    day, week, month, year;

    /** {@code start + n * interval units}; month/year additions clamp to the month end (Jan 31 + 1 month = Feb 28/29). */
    public LocalDate plus(LocalDate start, long n, int interval) {
        long amount = Math.multiplyExact(n, (long) interval);
        return switch (this) {
            case day -> start.plusDays(amount);
            case week -> start.plusWeeks(amount);
            case month -> start.plusMonths(amount);
            case year -> start.plusYears(amount);
        };
    }
}
