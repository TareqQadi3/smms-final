package com.smms.dto.response;

import com.smms.enums.TicketStatus;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

/** Minimal view of a possibly-duplicate ticket. Only tickets the caller may already see are listed. */
@Data @Builder
public class DuplicateCandidateResponse {
    UUID id;
    String ticketNumber;
    TicketStatus status;
    Instant createdAt;
}
