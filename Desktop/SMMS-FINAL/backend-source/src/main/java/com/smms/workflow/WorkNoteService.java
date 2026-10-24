package com.smms.workflow;

import com.smms.dto.response.TicketHistoryResponse;
import com.smms.entity.TicketHistory;
import com.smms.entity.User;
import com.smms.entity.WorkOrder;
import com.smms.enums.TicketStatus;
import com.smms.enums.UserRole;
import com.smms.exception.WorkflowException;
import com.smms.security.WorkOrderAccessPolicy;
import com.smms.service.WorkOrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;
import java.util.UUID;

/** Free-text work notes (history rows, status unchanged). */
@Service
@RequiredArgsConstructor
@Transactional
public class WorkNoteService {

    static final Set<UserRole> ROLES = Set.of(UserRole.technician, UserRole.control_room,
        UserRole.maintenance_manager, UserRole.hospital_supervisor, UserRole.system_admin);
    private static final Set<TicketStatus> TECHNICIAN_STATUSES =
        Set.of(TicketStatus.ASSIGNED, TicketStatus.IN_PROGRESS, TicketStatus.WAITING_MATERIALS);

    private final WorkOrderAccessPolicy accessPolicy;
    private final WorkOrderHistoryWriter historyWriter;
    private final com.smms.repository.TicketHistoryRepository historyRepo;

    public TicketHistoryResponse add(UUID id, String text, User actor) {
        if (actor == null || actor.getRole() == null || !ROLES.contains(actor.getRole())) {
            throw new AccessDeniedException("Role not permitted to add notes");
        }
        WorkOrder wo = accessPolicy.requireReadable(id, actor);
        if (actor.getRole() == UserRole.technician) {
            if (wo.getAssignedTo() == null || !wo.getAssignedTo().getId().equals(actor.getId())) {
                throw new AccessDeniedException("Only the assigned technician can add notes");
            }
            if (!TECHNICIAN_STATUSES.contains(wo.getStatus())) {
                throw new WorkflowException("Notes can only be added while the ticket is assigned, in progress or waiting for materials");
            }
        }
        TicketHistory h = historyWriter.note(wo, actor, text);
        return WorkOrderService.toHistoryResponse(h);
    }

    @Transactional(readOnly = true)
    public TicketHistoryResponse load(UUID historyId, UUID workOrderId, User actor) {
        accessPolicy.requireReadable(workOrderId, actor);
        return historyRepo.findById(historyId)
            .filter(h -> h.getWorkOrder() != null && workOrderId.equals(h.getWorkOrder().getId()))
            .map(WorkOrderService::toHistoryResponse)
            .orElseThrow(() -> new com.smms.exception.ResourceNotFoundException("Note not found"));
    }
}
