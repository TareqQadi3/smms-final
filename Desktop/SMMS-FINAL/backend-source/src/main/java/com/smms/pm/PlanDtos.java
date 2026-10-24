package com.smms.pm;

import com.smms.enums.SubType;
import com.smms.enums.TicketPriority;
import jakarta.validation.constraints.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Request and response shapes of /api/maintenance-plans. */
public final class PlanDtos {

    private PlanDtos() {}

    public record CreatePlanRequest(
        @NotNull UUID assetId,
        @NotNull UUID controlRoomId,
        @NotNull SubType subType,
        TicketPriority priority,
        @NotBlank @Size(max = 300) String title,
        @Size(max = 5000) String instructions,
        @NotNull FrequencyUnit frequencyUnit,
        @NotNull @Min(1) @Max(366) Integer frequencyInterval,
        @Min(0) @Max(60) Integer leadDays,
        @NotNull LocalDate startDate,
        /** Defaults to the caller; required for the platform administrator. */
        UUID ownerId) {}

    public record UpdatePlanRequest(
        @NotNull Long version,
        @NotNull UUID controlRoomId,
        @NotNull SubType subType,
        TicketPriority priority,
        @NotBlank @Size(max = 300) String title,
        @Size(max = 5000) String instructions,
        @NotNull FrequencyUnit frequencyUnit,
        @NotNull @Min(1) @Max(366) Integer frequencyInterval,
        @Min(0) @Max(60) Integer leadDays,
        /** Optional new next due date (today or later); a changed frequency re-anchors the series on it. */
        LocalDate nextDueDate,
        UUID ownerId) {}

    public record RunResponse(UUID id, LocalDate dueDate, RunStatus status, String source, UUID workOrderId,
                              String error, Instant overdueNotifiedAt, Instant createdAt) {
        static RunResponse of(MaintenancePlanRun r) {
            return new RunResponse(r.getId(), r.getDueDate(), r.getStatus(), r.getSource(), r.getWorkOrderId(),
                r.getError(), r.getOverdueNotifiedAt(), r.getCreatedAt());
        }
    }

    public record PlanResponse(UUID id, UUID assetId, String assetCode, String assetName, UUID centerId,
                               UUID controlRoomId, SubType subType, TicketPriority priority, String title,
                               String instructions, FrequencyUnit frequencyUnit, int frequencyInterval, int leadDays,
                               LocalDate startDate, LocalDate nextDueDate, boolean active, UUID ownerId,
                               String ownerName, boolean overdue, Long version, List<RunResponse> lastRuns) {}

    public record GenerateResponse(UUID planId, LocalDate dueDate, UUID workOrderId, LocalDate nextDueDate) {}
}
