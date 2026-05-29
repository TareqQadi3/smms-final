package com.smms.dto.response;

import com.smms.enums.TicketStatus;
import com.smms.enums.UserRole;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

@Data @Builder
public class TicketHistoryResponse {
    UUID id;
    TicketStatus fromStatus;
    TicketStatus toStatus;
    String stage;
    String note;
    Instant createdAt;
    ActorSummary actor;

    @Data @Builder
    public static class ActorSummary {
        UUID id;
        String fullName;
        UserRole role;
    }
}
