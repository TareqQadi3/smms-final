package com.smms.workflow;

import com.smms.dto.response.WorkOrderResponse;
import com.smms.entity.User;
import com.smms.entity.WorkOrder;
import com.smms.enums.RequesterConfirmation;
import com.smms.enums.TicketStatus;
import com.smms.exception.WorkflowException;
import com.smms.mapper.WorkOrderMapper;
import com.smms.repository.WorkOrderRepository;
import com.smms.security.WorkOrderAccessPolicy;
import com.smms.service.NotificationService;
import com.smms.util.InputSanitizer;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.UUID;

/**
 * The requester accepts or disputes a completed work order, once. Confirmation never closes the
 * ticket (the control room keeps that authority); reopen clears the verdict.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class RequesterConfirmationService {

    private final WorkOrderRepository workOrderRepo;
    private final WorkOrderAccessPolicy accessPolicy;
    private final WorkOrderHistoryWriter historyWriter;
    private final NotificationService notificationService;
    private final WorkOrderMapper mapper;
    private final EscalationRepository escalationRepo;
    private Clock clock = Clock.systemUTC();

    void setClock(Clock clock) { this.clock = clock; }

    public WorkOrderResponse confirm(UUID id, boolean accepted, String note, User actor) {
        WorkOrder wo = accessPolicy.requireReadable(id, actor);
        if (wo.getRequestedBy() == null || !wo.getRequestedBy().getId().equals(actor.getId())) {
            throw new AccessDeniedException("Only the requester can confirm the work");
        }
        if (wo.getStatus() != TicketStatus.COMPLETED) {
            throw new WorkflowException("Only a completed ticket can be confirmed");
        }
        if (wo.getRequesterConfirmation() != null) {
            throw new WorkflowException("The requester has already confirmed this ticket");
        }
        String clean = InputSanitizer.clean(note, 1000);
        if (!accepted && (clean == null || clean.isBlank())) {
            throw new IllegalArgumentException("A note is required when the work is disputed");
        }
        wo.setRequesterConfirmation(accepted ? RequesterConfirmation.accepted : RequesterConfirmation.disputed);
        wo.setRequesterConfirmedAt(clock.instant());
        workOrderRepo.saveAndFlush(wo);
        historyWriter.note(wo, actor, accepted
            ? "Requester accepted the work" + (clean == null || clean.isBlank() ? "" : ": " + clean)
            : "Requester disputed the work: " + clean);
        notificationService.notifyRequesterVerdict(wo, actor, accepted);
        WorkOrderResponse r = mapper.toResponse(wo);
        if (r != null) {
            Integer lvl = escalationRepo.maxLevel(wo.getId());
            r.setEscalationLevel(lvl == null ? 0 : lvl);
        }
        return r;
    }
}
