package com.smms.dto.response;

import com.smms.enums.ApprovalDecision;
import com.smms.enums.SubType;
import com.smms.enums.TicketPriority;
import com.smms.enums.TicketStatus;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Data @Builder
public class WorkOrderResponse {
    UUID id;
    String ticketNumber;
    TicketStatus status;
    String currentStage;
    TicketPriority priority;
    SubType subType;
    String title;
    String description;
    String locationDetail;
    boolean needsMaterials;
    boolean slaBreached;
    boolean locked;
    int reopenedCount;
    Instant slaDeadline;
    Instant completedDate;
    Instant closedAt;
    Instant createdAt;
    UserSummary requestedBy;
    UserSummary assignedTo;
    CenterSummary center;
    ControlRoomSummary controlRoom;
    ApprovalDecision approvalDecision;
    String techReport;
    String warehouseNotes;
    String rejectionReason;
    /** Only set on the create response: visible tickets that look like duplicates (non-blocking warning). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    List<DuplicateCandidateResponse> possibleDuplicates;
}
