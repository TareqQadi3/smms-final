package com.smms.reports;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReportRangeTest {

    // 2026-03-10 22:30 UTC is already 2026-03-11 01:30 in Riyadh
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-03-10T22:30:00Z"), ZoneOffset.UTC);

    @Test
    void defaultsToLast30DaysEndingTodayInRiyadh() {
        ReportRange r = ReportRange.parse(null, null, CLOCK);
        assertThat(r.to()).isEqualTo(LocalDate.of(2026, 3, 11));
        assertThat(r.from()).isEqualTo(LocalDate.of(2026, 2, 10));
    }

    @Test
    void instantsAreRiyadhDayBoundaries() {
        ReportRange r = new ReportRange(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 1));
        assertThat(r.start()).isEqualTo(Instant.parse("2026-02-28T21:00:00Z"));
        assertThat(r.endExclusive()).isEqualTo(Instant.parse("2026-03-01T21:00:00Z"));
    }

    @Test
    void oneSidedRangeIsRejected() {
        assertThatThrownBy(() -> ReportRange.parse(LocalDate.of(2026, 1, 1), null, CLOCK)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ReportRange.parse(null, LocalDate.of(2026, 1, 1), CLOCK)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void fromAfterToIsRejected() {
        assertThatThrownBy(() -> new ReportRange(LocalDate.of(2026, 2, 2), LocalDate.of(2026, 2, 1)))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void spanIsCappedAt366DaysInclusive() {
        LocalDate from = LocalDate.of(2025, 1, 1);
        assertThat(new ReportRange(from, from.plusDays(365)).to()).isEqualTo(LocalDate.of(2026, 1, 1)); // 366 days
        assertThatThrownBy(() -> new ReportRange(from, from.plusDays(366))).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("366");
    }

    @Test
    void ancientDatesAreRejected() {
        assertThatThrownBy(() -> new ReportRange(LocalDate.of(1990, 1, 1), LocalDate.of(1990, 1, 2)))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
