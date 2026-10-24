package com.smms.workflow;

import com.smms.dto.response.WorkOrderResponse;
import com.smms.entity.User;
import com.smms.entity.WorkOrder;
import com.smms.enums.TicketStatus;
import com.smms.enums.UserRole;
import com.smms.exception.ConflictException;
import com.smms.exception.WorkflowException;
import com.smms.repository.WorkOrderRepository;
import com.smms.security.WorkOrderAccessPolicy;
import com.smms.service.NotificationService;
import com.smms.util.InputSanitizer;
import com.smms.workflow.WorkflowDtos.EscalationResponse;
import com.smms.workflow.WorkflowDtos.UserRef;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Escalation process. Level 1 at SLA breach, level 2 when the ticket stays overdue for
 * {@code level2-factor * SLA hours} (both automatic), level 3 manual only. Every level is inserted
 * first with ON CONFLICT DO NOTHING on (work order, level): only the caller whose insert succeeded
 * notifies, so a repeated scheduler pass, a second replica or a concurrent manual escalation never
 * duplicate a level or its notifications. Paused tickets are never breached or escalated.
 */
@Slf4j
@Service
public class EscalationService {

    static final List<String> TERMINAL = List.of(
        TicketStatus.COMPLETED.getDbValue(), TicketStatus.CLOSED.getDbValue(), TicketStatus.CANCELLED.getDbValue());
    static final Set<UserRole> MANUAL_ROLES = Set.of(UserRole.control_room, UserRole.maintenance_manager,
        UserRole.hospital_supervisor, UserRole.electrical_manager, UserRole.system_admin);
    public static final int MAX_LEVEL = 3;

    private final WorkOrderRepository workOrderRepo;
    private final EscalationRepository escalationRepo;
    private final NotificationService notificationService;
    private final WorkOrderAccessPolicy accessPolicy;
    private final WorkOrderHistoryWriter historyWriter;
    private final EscalationProperties props;
    private final TransactionTemplate requiresNew;
    private Clock clock = Clock.systemUTC();

    public EscalationService(WorkOrderRepository workOrderRepo, EscalationRepository escalationRepo,
                             NotificationService notificationService, WorkOrderAccessPolicy accessPolicy,
                             WorkOrderHistoryWriter historyWriter, EscalationProperties props,
                             PlatformTransactionManager txManager) {
        this.workOrderRepo = workOrderRepo;
        this.escalationRepo = escalationRepo;
        this.notificationService = notificationService;
        this.accessPolicy = accessPolicy;
        this.historyWriter = historyWriter;
        this.props = props;
        this.requiresNew = new TransactionTemplate(txManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    void setClock(Clock clock) { this.clock = clock; }

    // ── Read ──────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<EscalationResponse> list(UUID workOrderId, User actor) {
        accessPolicy.requireReadable(workOrderId, actor);
        return escalationRepo.findAllForWorkOrder(workOrderId).stream()
            .map(e -> new EscalationResponse(e.getId(), e.getLevel(), e.getTriggerKind(),
                UserRef.of(e.getTriggeredBy()), e.getReason(), e.getNotifiedCount(), e.getCreatedAt()))
            .toList();
    }

    // ── Manual ────────────────────────────────────────────

    /** Raises the next level (max 3). Roles: control_room, maintenance_manager, hospital_supervisor, electrical_manager, system_admin. */
    @Transactional
    public EscalationResponse escalate(UUID workOrderId, String reason, User actor) {
        if (actor == null || actor.getRole() == null || !MANUAL_ROLES.contains(actor.getRole())) {
            throw new AccessDeniedException("Role not permitted to escalate tickets");
        }
        WorkOrder wo = accessPolicy.requireReadable(workOrderId, actor);
        if (wo.getTenantId() == null) throw new WorkflowException("Ticket has no tenant");
        if (wo.getStatus() == TicketStatus.COMPLETED || wo.getStatus() == TicketStatus.CLOSED
                || wo.getStatus() == TicketStatus.CANCELLED) {
            throw new WorkflowException("A " + wo.getStatus().getDbValue() + " ticket cannot be escalated");
        }
        if (wo.isSlaPaused()) {
            throw new WorkflowException("The SLA is paused (waiting for information); the ticket cannot be escalated");
        }
        Integer max = escalationRepo.maxLevel(workOrderId);
        int level = (max == null ? 0 : max) + 1;
        if (level > MAX_LEVEL) throw new WorkflowException("The ticket is already at the maximum escalation level");
        String clean = InputSanitizer.clean(reason, 1000);
        UUID id = UUID.randomUUID();
        if (escalationRepo.insertIgnore(id, wo.getTenantId(), wo.getId(), level, "manual", actor.getId(), clean) == 0) {
            throw new ConflictException("The ticket was escalated concurrently, reload and try again");
        }
        int n = notificationService.notifyEscalation(wo, level);
        escalationRepo.setNotified(id, n);
        historyWriter.note(wo, actor, "Escalated to level " + level + ": " + clean);
        log.info("AUDIT manual escalation: workOrder={} level={} actor={} notified={}", wo.getId(), level, actor.getId(), n);
        return new EscalationResponse(id, level, "manual", UserRef.of(actor), clean, n, clock.instant());
    }

    /** Highest level of a ticket (0 = none), for WorkOrderResponse.escalationLevel. */
    @Transactional(readOnly = true)
    public int levelOf(UUID workOrderId) {
        Integer m = escalationRepo.maxLevel(workOrderId);
        return m == null ? 0 : m;
    }

    // ── Automatic (scheduler; no TenantContext, everything filters by the ticket's tenant) ──

    /** One scheduler pass. Each ticket runs in its own transaction: a failure never blocks the others. */
    public void runScheduledPass() {
        if (!props.isEnabled()) return;
        Instant now = clock.instant();
        List<UUID> breached = requiresNew.execute(s ->
            workOrderRepo.findSlaBreached(now, TERMINAL).stream().map(WorkOrder::getId).toList());
        int l1 = 0;
        for (UUID id : breached == null ? List.<UUID>of() : breached) {
            if (safely(() -> breachOne(id, now))) l1++;
        }
        List<UUID> overdue = requiresNew.execute(s ->
            workOrderRepo.findLevel2Candidates(now, TERMINAL).stream().map(WorkOrder::getId).toList());
        int l2 = 0;
        for (UUID id : overdue == null ? List.<UUID>of() : overdue) {
            if (safely(() -> overdueOne(id, now))) l2++;
        }
        if (l1 + l2 > 0) log.info("SLA escalation pass: {} breached, {} level-2 escalations", l1, l2);
    }

    private boolean safely(java.util.function.Supplier<Boolean> step) {
        try {
            return Boolean.TRUE.equals(requiresNew.execute(s -> step.get()));
        } catch (RuntimeException e) {
            // includes an optimistic-lock failure when the ticket changed under us: next pass retries
            log.warn("SLA escalation step skipped: {}", e.getClass().getSimpleName());
            return false;
        }
    }

    /** Re-validates under the transaction (the ticket may have been answered or closed meanwhile). */
    boolean breachOne(UUID id, Instant now) {
        WorkOrder wo = workOrderRepo.findById(id).orElse(null);
        if (wo == null || wo.getDeletedAt() != null || wo.getTenantId() == null) return false;
        if (wo.isSlaBreached() || wo.isSlaPaused() || wo.getSlaDeadline() == null
                || !wo.getSlaDeadline().isBefore(now) || terminal(wo)) return false;
        // entity update (not a bulk update): the @Version check loses against a concurrent provide-info
        wo.setSlaBreached(true);
        workOrderRepo.saveAndFlush(wo);
        UUID rowId = UUID.randomUUID();
        if (escalationRepo.insertIgnore(rowId, wo.getTenantId(), wo.getId(), 1, "sla_breach", null,
                "SLA deadline passed") == 0) return true;
        int n = props.notifies(wo.getPriority()) ? notificationService.notifyEscalation(wo, 1) : 0;
        escalationRepo.setNotified(rowId, n);
        return true;
    }

    boolean overdueOne(UUID id, Instant now) {
        WorkOrder wo = workOrderRepo.findById(id).orElse(null);
        if (wo == null || wo.getDeletedAt() != null || wo.getTenantId() == null || wo.getSlaDeadline() == null) return false;
        if (!wo.isSlaBreached() || wo.isSlaPaused() || terminal(wo)) return false;
        long minutes = Math.round(props.getLevel2Factor() * SlaPause.slaHours(wo.getPriority()) * 60);
        if (!now.isAfter(wo.getSlaDeadline().plus(Duration.ofMinutes(minutes)))) return false;
        UUID rowId = UUID.randomUUID();
        if (escalationRepo.insertIgnore(rowId, wo.getTenantId(), wo.getId(), 2, "sla_overdue", null,
                "Overdue beyond the level-2 threshold") == 0) return false;
        int n = notificationService.notifyEscalation(wo, 2);
        escalationRepo.setNotified(rowId, n);
        return true;
    }

    private static boolean terminal(WorkOrder wo) {
        return TERMINAL.contains(wo.getStatus().getDbValue());
    }

    /** Used by the emergency flow: level 1 with trigger 'emergency' unless a level 1 exists already. */
    public boolean recordEmergencyLevel(WorkOrder wo, User actor, String reason) {
        return escalationRepo.insertIgnore(UUID.randomUUID(), wo.getTenantId(), wo.getId(), 1, "emergency",
            actor.getId(), reason) == 1;
    }
}
