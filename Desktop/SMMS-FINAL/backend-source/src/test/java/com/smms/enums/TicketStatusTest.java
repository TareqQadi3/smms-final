package com.smms.enums;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for TicketStatus enum — particularly the DB converter used by JPA.
 */
class TicketStatusTest {

    @ParameterizedTest(name = "DB value \"{0}\" → {1}")
    @CsvSource({
        "new,               NEW",
        "under_review,      UNDER_REVIEW",
        "assigned,          ASSIGNED",
        "in_progress,       IN_PROGRESS",
        "waiting_materials, WAITING_MATERIALS",
        "waiting_approval,  WAITING_APPROVAL",
        "completed,         COMPLETED",
        "closed,            CLOSED",
        "cancelled,         CANCELLED",
    })
    void fromDbValueMapsAllStatuses(String dbValue, TicketStatus expected) {
        assertThat(TicketStatus.fromDbValue(dbValue.trim())).isEqualTo(expected);
    }

    @Test
    void fromDbValueThrowsOnUnknown() {
        assertThatThrownBy(() -> TicketStatus.fromDbValue("unknown_status"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Invalid status");
    }

    @Test
    void toStringReturnsDbValue() {
        assertThat(TicketStatus.NEW.toString()).isEqualTo("new");
        assertThat(TicketStatus.WAITING_MATERIALS.toString()).isEqualTo("waiting_materials");
        assertThat(TicketStatus.CLOSED.toString()).isEqualTo("closed");
    }

    @Test
    void everyStatusHasRoundTrip() {
        for (TicketStatus status : TicketStatus.values()) {
            String dbValue = status.getDbValue();
            assertThat(TicketStatus.fromDbValue(dbValue))
                .as("fromDbValue(getDbValue()) should round-trip for %s", status)
                .isEqualTo(status);
        }
    }
}
