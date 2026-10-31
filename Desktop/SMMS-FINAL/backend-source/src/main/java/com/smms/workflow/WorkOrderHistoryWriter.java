package com.smms.workflow;

import com.smms.entity.TicketHistory;
import com.smms.entity.User;
import com.smms.entity.WorkOrder;
import com.smms.enums.TicketStatus;
import com.smms.repository.TicketHistoryRepository;
import com.smms.util.InputSanitizer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Single place that appends audit rows to {@code ticket_history}. Joins the caller's transaction
 * (no transaction of its own), so the history row commits or rolls back with the work-order change.
 */
@Component
@RequiredArgsConstructor
public class WorkOrderHistoryWriter {

    /** Stage label of free-text work notes (the status does not change). */
    public static final String STAGE_NOTE = "note";

    private final TicketHistoryRepository historyRepo;

    /** One status-transition row; the stage is the work order's current stage at call time. */
    public TicketHistory record(WorkOrder wo, TicketStatus from, TicketStatus to, User actor, String note) {
        return historyRepo.save(TicketHistory.builder()
            .workOrder(wo)
            .actor(actor)
            .actorRole(actor.getRole())
            .fromStatus(from)
            .toStatus(to)
            .stage(wo.getCurrentStage())
            .note(InputSanitizer.clean(note, 1000))
            .build());
    }

    /** A note row: from = to = the current status, stage {@value #STAGE_NOTE}. */
    public TicketHistory note(WorkOrder wo, User actor, String note) {
        return historyRepo.save(TicketHistory.builder()
            .workOrder(wo)
            .actor(actor)
            .actorRole(actor.getRole())
            .fromStatus(wo.getStatus())
            .toStatus(wo.getStatus())
            .stage(STAGE_NOTE)
            .note(InputSanitizer.clean(note, 1000))
            .build());
    }
}
