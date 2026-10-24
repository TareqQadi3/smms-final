package com.smms.reports;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Response shapes of the operational reports (JSON). CSV output is derived from the same rows. */
public final class ReportModels {

    private ReportModels() {}

    public record Group(String key, String label, long count) {}

    public record Volume(LocalDate from, LocalDate to, String groupBy, List<Group> groups, long total) {}

    public record Turnaround(String key, long completedCount, BigDecimal avgHours, BigDecimal p50Hours, BigDecimal p90Hours) {}

    public record TurnaroundReport(LocalDate from, LocalDate to, String groupBy, List<Turnaround> rows) {}

    public record SlaRow(String priority, long due, long metOnTime, long breached, BigDecimal compliancePct,
                         BigDecimal avgPausedHours, long openBreached) {}

    public record SlaReport(LocalDate from, LocalDate to, List<SlaRow> rows) {}

    public record EscalationGroup(int level, String trigger, long count) {}

    public record DayCount(String day, long count) {}

    /** {@code available=false}: the escalation table does not exist yet (older schema); no data, not an error. */
    public record EscalationReport(LocalDate from, LocalDate to, boolean available, List<EscalationGroup> groups,
                                   List<DayCount> byDay) {}

    public record UserRef(UUID id, String fullName) {}

    public record TechnicianRow(UserRef technician, long assigned, long completed, long open, long reopened,
                                BigDecimal avgTurnaroundHours, ByPriority completedByPriority, long emergencyHandled) {}

    public record ByPriority(long critical, long high, long medium, long low) {}

    public record Page<T>(List<T> content, int page, int size, long totalElements, int totalPages) {}

    public record OverdueRow(UUID workOrderId, String ticketNumber, String title, String priority, String status,
                             UUID centerId, String centerName, UserRef assignedTo, Instant slaDeadline,
                             BigDecimal hoursOverdue) {}
}
