package com.smms.workflow;

import com.smms.dto.response.WorkOrderResponse;
import com.smms.entity.User;
import com.smms.entity.WorkOrder;
import com.smms.enums.TicketPriority;
import com.smms.enums.TicketStatus;
import com.smms.enums.UserRole;
import com.smms.exception.WorkflowException;
import com.smms.mapper.WorkOrderMapper;
import com.smms.repository.WorkOrderRepository;
import com.smms.security.WorkOrderAccessPolicy;
import com.smms.service.NotificationService;
import com.smms.util.InputSanitizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * Emergency mode. Declare (control_room, maintenance_manager, hospital_supervisor, system_admin):
 * only on open tickets, priority becomes critical and the SLA deadline can only move EARLIER
 * (least of the current deadline and now + the critical window). Clear (maintenance_manager,
 * hospital_supervisor, system_admin): the flag goes off, priority and SLA are never silently
 * relaxed. Both write a history row with the mandatory reason.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class EmergencyService {

    static final Set<UserRole> DECLARE_ROLES = Set.of(UserRole.control_room, UserRole.maintenance_manager,
        UserRole.hospital_supervisor, UserRole.system_admin);
    static final Set<UserRole> CLEAR_ROLES = Set.of(UserRole.maintenance_manager, UserRole.hospital_supervisor,
        UserRole.system_admin);

    private final WorkOrderRepository workOrderRepo;
    private final WorkOrderAccessPolicy accessPolicy;
    private final WorkOrderHistoryWriter historyWriter;
    private final NotificationService notificationService;
    private final EscalationService escalationService;
    private final EscalationRepository escalationRepo;
    private final WorkOrderMapper mapper;
    private Clock clock = Clock.systemUTC();

    void setClock(Clock clock) { this.clock = clock; }

    public WorkOrderResponse declare(UUID id, String reason, User actor) {
        require(actor, DECLARE_ROLES, "declare an emergency");
        WorkOrder wo = accessPolicy.requireReadable(id, actor);
        if (wo.getTenantId() == null) throw new WorkflowException("Ticket has no tenant");
        if (terminal(wo.getStatus())) throw new WorkflowException("An emergency cannot be declared on a "
            + wo.getStatus().getDbValue() + " ticket");
        if (wo.isEmergency()) throw new WorkflowException("The ticket is already an emergency");
        String clean = InputSanitizer.clean(reason, 1000);
        Instant now = clock.instant();
        Instant window = now.plusSeconds(SlaPause.slaHours(TicketPriority.critical) * 3600);
        wo.setEmergency(true);
        wo.setEmergencyDeclaredAt(now);
        wo.setEmergencyDeclaredBy(actor);
        wo.setPriority(TicketPriority.critical);
        // never extends: a deadline that is already earlier stays (paused tickets: the window then
        // runs from the resume, because resume shifts the deadline by the paused time)
        if (wo.getSlaDeadline() == null || window.isBefore(wo.getSlaDeadline())) wo.setSlaDeadline(window);
        workOrderRepo.saveAndFlush(wo);
        escalationService.recordEmergencyLevel(wo, actor, clean);
        int n = notificationService.notifyEmergency(wo, actor);
        historyWriter.note(wo, actor, "Emergency declared: " + clean);
        log.info("AUDIT emergency declared: workOrder={} actor={} notified={}", wo.getId(), actor.getId(), n);
        return response(wo);
    }

    public WorkOrderResponse clear(UUID id, String reason, User actor) {
        require(actor, CLEAR_ROLES, "clear an emergency");
        WorkOrder wo = accessPolicy.requireReadable(id, actor);
        if (!wo.isEmergency()) throw new WorkflowException("The ticket is not an emergency");
        String clean = InputSanitizer.clean(reason, 1000);
        wo.setEmergency(false);
        workOrderRepo.saveAndFlush(wo);
        historyWriter.note(wo, actor, "Emergency cleared: " + clean);
        log.info("AUDIT emergency cleared: workOrder={} actor={}", wo.getId(), actor.getId());
        return response(wo);
    }

    private WorkOrderResponse response(WorkOrder wo) {
        WorkOrderResponse r = mapper.toResponse(wo);
        if (r != null) {
            Integer lvl = escalationRepo.maxLevel(wo.getId());
            r.setEscalationLevel(lvl == null ? 0 : lvl);
        }
        return r;
    }

    private static void require(User actor, Set<UserRole> roles, String what) {
        if (actor == null || actor.getRole() == null || !roles.contains(actor.getRole())) {
            throw new AccessDeniedException("Role not permitted to " + what);
        }
    }

    private static boolean terminal(TicketStatus s) {
        return s == TicketStatus.COMPLETED || s == TicketStatus.CLOSED || s == TicketStatus.CANCELLED;
    }
}
