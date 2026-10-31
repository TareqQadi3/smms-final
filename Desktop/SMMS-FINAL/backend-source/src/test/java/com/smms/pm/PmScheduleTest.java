package com.smms.pm;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PmScheduleTest {

    private static LocalDate d(String s) { return LocalDate.parse(s); }

    @Test
    void monthEndClampsAndDoesNotDrift() {
        LocalDate anchor = d("2025-01-31");
        LocalDate feb = PmSchedule.nextAfter(anchor, FrequencyUnit.month, 1, anchor);
        assertThat(feb).isEqualTo(d("2025-02-28"));
        LocalDate mar = PmSchedule.nextAfter(anchor, FrequencyUnit.month, 1, feb);
        assertThat(mar).isEqualTo(d("2025-03-31")); // anchored on Jan 31, not Feb 28
        assertThat(PmSchedule.nextAfter(anchor, FrequencyUnit.month, 1, mar)).isEqualTo(d("2025-04-30"));
    }

    @Test
    void leapYearFebruary() {
        assertThat(PmSchedule.nextAfter(d("2024-01-31"), FrequencyUnit.month, 1, d("2024-01-31"))).isEqualTo(d("2024-02-29"));
        assertThat(PmSchedule.nextAfter(d("2024-02-29"), FrequencyUnit.year, 1, d("2024-02-29"))).isEqualTo(d("2025-02-28"));
        assertThat(PmSchedule.nextAfter(d("2024-02-29"), FrequencyUnit.year, 1, d("2025-02-28"))).isEqualTo(d("2026-02-28"));
        assertThat(PmSchedule.nextAfter(d("2024-02-29"), FrequencyUnit.year, 4, d("2024-02-29"))).isEqualTo(d("2028-02-29"));
    }

    @Test
    void quarterAndMultiWeekIntervals() {
        assertThat(PmSchedule.nextAfter(d("2025-11-30"), FrequencyUnit.month, 3, d("2025-11-30"))).isEqualTo(d("2026-02-28"));
        assertThat(PmSchedule.nextAfter(d("2025-01-01"), FrequencyUnit.week, 2, d("2025-01-01"))).isEqualTo(d("2025-01-15"));
        assertThat(PmSchedule.nextAfter(d("2025-01-01"), FrequencyUnit.day, 366, d("2025-01-01"))).isEqualTo(d("2026-01-02"));
    }

    @Test
    void daysAcrossDaylightSavingChangesAreCalendarDays() {
        // LocalDate maths has no hours: Europe/US DST weekends add exactly one calendar day
        assertThat(PmSchedule.nextAfter(d("2026-03-28"), FrequencyUnit.day, 1, d("2026-03-28"))).isEqualTo(d("2026-03-29"));
        assertThat(PmSchedule.nextAfter(d("2026-10-24"), FrequencyUnit.week, 1, d("2026-10-24"))).isEqualTo(d("2026-10-31"));
        assertThat(PmSchedule.nextAfter(d("2026-10-31"), FrequencyUnit.week, 1, d("2026-10-31"))).isEqualTo(d("2026-11-07"));
    }

    @Test
    void nextAfterSkipsToTheFirstOccurrenceStrictlyAfterAFarCurrentDate() {
        assertThat(PmSchedule.nextAfter(d("2025-01-31"), FrequencyUnit.month, 1, d("2025-06-15"))).isEqualTo(d("2025-06-30"));
        assertThat(PmSchedule.nextAfter(d("2025-01-01"), FrequencyUnit.day, 7, d("2025-01-08"))).isEqualTo(d("2025-01-15"));
        assertThat(PmSchedule.nextAfter(d("2025-01-01"), FrequencyUnit.day, 7, d("2025-01-09"))).isEqualTo(d("2025-01-15"));
        assertThat(PmSchedule.nextAfter(d("2020-01-01"), FrequencyUnit.day, 1, d("2030-01-01"))).isEqualTo(d("2030-01-02"));
    }

    @Test
    void currentBeforeAnchorYieldsTheAnchorWhenStrictlyAfter() {
        assertThat(PmSchedule.nextAfter(d("2025-03-01"), FrequencyUnit.month, 1, d("2025-01-01"))).isEqualTo(d("2025-03-01"));
    }

    @Test
    void firstOnOrAfterKeepsTheSeries() {
        assertThat(PmSchedule.firstOnOrAfter(d("2025-01-31"), FrequencyUnit.month, 1, d("2025-03-31"))).isEqualTo(d("2025-03-31"));
        assertThat(PmSchedule.firstOnOrAfter(d("2025-01-31"), FrequencyUnit.month, 1, d("2025-03-01"))).isEqualTo(d("2025-03-31"));
        assertThat(PmSchedule.firstOnOrAfter(d("2025-06-01"), FrequencyUnit.month, 1, d("2025-01-01"))).isEqualTo(d("2025-06-01"));
    }

    @Test
    void dueWindowUsesLeadDays() {
        assertThat(PmSchedule.isDue(d("2025-02-10"), 0, d("2025-02-10"))).isTrue();
        assertThat(PmSchedule.isDue(d("2025-02-10"), 0, d("2025-02-09"))).isFalse();
        assertThat(PmSchedule.isDue(d("2025-02-10"), 3, d("2025-02-07"))).isTrue();
        assertThat(PmSchedule.isDue(d("2025-02-10"), 3, d("2025-02-06"))).isFalse();
    }

    @Test
    void invalidIntervalIsRejected() {
        assertThatThrownBy(() -> PmSchedule.nextAfter(d("2025-01-01"), FrequencyUnit.day, 0, d("2025-01-01")))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
