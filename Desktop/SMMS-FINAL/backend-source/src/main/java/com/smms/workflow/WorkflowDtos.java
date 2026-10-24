package com.smms.workflow;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.smms.entity.User;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

/** Request and response records of the W1 workflow endpoints. */
public final class WorkflowDtos {
    private WorkflowDtos() {}

    public record UserRef(UUID id, String fullName, String fullNameAr) {
        public static UserRef of(User u) {
            return u == null ? null : new UserRef(u.getId(), u.getFullName(), u.getFullNameAr());
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record InfoRequestResponse(UUID id, String question, UserRef requestedBy, Instant requestedAt,
                                      String answer, UserRef answeredBy, Instant answeredAt,
                                      Instant cancelledAt, Long pausedSeconds) {}

    public record EscalationResponse(UUID id, int level, String trigger, UserRef triggeredBy, String reason,
                                     Integer notifiedCount, Instant createdAt) {}

    public record EscalateRequest(@NotBlank @Size(min = 5, max = 1000) String reason) {}

    public record ConfirmRequest(@NotNull Boolean accepted, @Size(max = 1000) String note) {}

    public record RatingRequest(@NotNull @jakarta.validation.constraints.Min(1) @jakarta.validation.constraints.Max(5) Integer score,
                                @Size(max = 1000) String comment) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RatingResponse(UUID id, int score, String comment, Instant createdAt) {}

    public record StatusLinkResponse(String token, String path, Instant expiresAt) {}

    public record ReasonRequest(@NotBlank @Size(min = 5, max = 1000) String reason) {}

    public record NoteRequest(@NotBlank @Size(max = 2000) String text) {}

    /** Whitelist of what the public endpoint may reveal. Adding a field here is a privacy decision. */
    public record PublicStatusResponse(String ticketNumber, String status, String statusGroup,
                                       Instant createdAt, Instant lastUpdatedAt, boolean completed) {}
}
