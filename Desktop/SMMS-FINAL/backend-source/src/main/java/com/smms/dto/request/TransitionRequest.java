package com.smms.dto.request;

import com.smms.enums.TicketStatus;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.Instant;

@Data
public class TransitionRequest {
    @NotNull TicketStatus targetStatus;
    String note;
    Instant scheduledDate;
}
