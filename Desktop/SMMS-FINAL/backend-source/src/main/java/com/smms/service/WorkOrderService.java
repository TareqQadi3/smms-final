package com.smms.service;

import com.smms.dto.request.CreateWorkOrderRequest;
import com.smms.util.InputSanitizer;
import com.smms.dto.request.TransitionRequest;
import com.smms.dto.response.TicketHistoryResponse;
import com.smms.dto.response.WorkOrderResponse;
import com.smms.entity.*;
import com.smms.enums.*;
import com.smms.exception.*;
import com.smms.mapper.WorkOrderMapper;
import com.smms.repository.*;
import com.smms.security.TenantSecurityService;
import com.smms.security.WorkOrderAccessPolicy;
import com.smms.security.WorkOrderScope;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional
public class WorkOrderService {

    private final WorkOrderRepository workOrderRepo;
    private final TicketHistoryRepository historyRepo;
    private final WorkflowEngine workflowEngine;
    private final NotificationService notificationService;
    private final WorkOrderMapper mapper;
    private final PlanEnforcementService planEnforcement;
    private final TenantSecurityService tenantSecurity;
    private final WorkOrderAccessPolicy accessPolicy;
    private final EntityManager entityManager;

    // ── Create ────────────────────────────────────────────

    public WorkOrderResponse create(CreateWorkOrderRequest req, User actor) {
        planEnforcement.checkTicketLimit();

        ControlRoom cr = tenantSecurity.requireControlRoom(req.getControlRoomId());

        // Role/center/control-room scope check (shared policy, 403 when out of scope)
        accessPolicy.assertCanCreateIn(cr, actor);

        validateSubmitterRole(actor.getRole(), cr.getType());

        WorkOrder wo = WorkOrder.builder()
            .center(cr.getCenter())
            .controlRoom(cr)
            .subType(req.getSubType())
            .title(InputSanitizer.clean(req.getTitle(), 500))
            .description(InputSanitizer.clean(req.getDescription()))
            .locationDetail(InputSanitizer.clean(req.getLocationDetail(), 300))
            .priority(req.getPriority())
            .requestedBy(actor)
            .createdByRole(actor.getRole())
            .status(TicketStatus.NEW)
            .currentStage("new")
            .build();

        // The control room is already tenant-checked; inherit its tenant (also correct for platform-admin callers)
        wo.setTenantId(cr.getTenantId());
        WorkOrder saved = workOrderRepo.save(wo);
        // ticket_number and sla_deadline are filled by DB triggers on INSERT; flush the insert and
        // reload the row so the response carries those values instead of nulls.
        workOrderRepo.flush();
        entityManager.refresh(saved);
        recordHistory(saved, null, TicketStatus.NEW, actor, "Ticket created");
        notificationService.notifyControlRoom(saved);
        return mapper.toResponse(saved);
    }

    // ── Transition ────────────────────────────────────────

    public WorkOrderResponse transition(UUID id, TransitionRequest req, User actor) {
        WorkOrder wo = getActiveWorkOrder(id, actor);

        if (wo.isLocked() && !wo.getLockedBy().getId().equals(actor.getId())) {
            throw new TicketLockedException("Ticket is locked by another user");
        }

        TicketStatus from = wo.getStatus();
        TicketStatus to   = req.getTargetStatus();

        rejectDedicatedEndpointTransition(from, to, actor.getRole());
        workflowEngine.validate(from, to, actor.getRole());

        TicketStatus previous = wo.getStatus();
        wo.setStatus(to);
        wo.setCurrentStage(workflowEngine.resolveStage(to));

        applyTransitionSideEffects(wo, req, actor, to);

        workOrderRepo.save(wo);
        recordHistory(wo, previous, to, actor, req.getNote());
        notificationService.notifyTransition(wo, actor);

        return mapper.toResponse(wo);
    }

    /**
     * Guards the generic /transition path against moves that have dedicated endpoints which
     * record required data (assignee, approval decision, reopen counter).
     * Conditions (reject when any holds):
     *  - to == ASSIGNED: needs a technician, only /assign sets one (else the ticket is orphaned).
     *  - from == WAITING_APPROVAL: approval decisions go through /approval (records approvedBy and
     *    decision). system_admin is exempt because the UI offers admin this move only via /transition
     *    and /approval is restricted to operating_company_manager.
     *  - from == COMPLETED and to == IN_PROGRESS: reopen goes through /reopen (counter + reason).
     */
    static void rejectDedicatedEndpointTransition(TicketStatus from, TicketStatus to, UserRole role) {
        if (to == TicketStatus.ASSIGNED) {
            throw new IllegalArgumentException(
                "Use POST /api/work-orders/{id}/assign to assign a technician; generic transition to assigned is not allowed");
        }
        if (from == TicketStatus.WAITING_APPROVAL && role != UserRole.system_admin) {
            throw new IllegalArgumentException(
                "Use POST /api/work-orders/{id}/approval to decide on approval requests");
        }
        if (from == TicketStatus.COMPLETED && to == TicketStatus.IN_PROGRESS) {
            throw new IllegalArgumentException(
                "Use POST /api/work-orders/{id}/reopen to reopen a completed ticket");
        }
    }

    // ── Assign technician ─────────────────────────────────

    public WorkOrderResponse assign(UUID id, UUID technicianId, String note, User actor) {
        WorkOrder wo = getActiveWorkOrder(id, actor);
        if (wo.getStatus() != TicketStatus.UNDER_REVIEW) {
            throw new WorkflowException("Can only assign from under_review status");
        }
        User tech = tenantSecurity.requireUser(technicianId, UserRole.technician);
        // A technician can only be assigned inside the ticket's own center; otherwise behave as unknown
        if (tech.getCenter() == null || wo.getCenter() == null
                || !tech.getCenter().getId().equals(wo.getCenter().getId())) {
            throw new ResourceNotFoundException("User not found");
        }

        TicketStatus prev = wo.getStatus();
        wo.setAssignedTo(tech);
        wo.setStatus(TicketStatus.ASSIGNED);
        wo.setCurrentStage("assigned");

        workOrderRepo.save(wo);
        recordHistory(wo, prev, TicketStatus.ASSIGNED, actor, note);
        notificationService.notifyUser(tech, wo, "ASSIGNED", "Ticket assigned to you");
        return mapper.toResponse(wo);
    }

    // ── Warehouse decision ────────────────────────────────

    public WorkOrderResponse warehouseAction(UUID id, boolean materialsAvailable,
                                              String notes, User actor) {
        WorkOrder wo = getActiveWorkOrder(id, actor);
        if (wo.getStatus() != TicketStatus.WAITING_MATERIALS) {
            throw new WorkflowException("Ticket not in waiting_materials status");
        }
        wo.setWarehouseNotes(notes);
        TicketStatus prev = wo.getStatus();

        if (materialsAvailable) {
            wo.setStatus(TicketStatus.IN_PROGRESS);
            wo.setCurrentStage("in_progress");
            recordHistory(wo, prev, TicketStatus.IN_PROGRESS, actor, "Materials provided");
        } else {
            wo.setNeedsMaterials(true);
            wo.setMaterialRequiredAt(Instant.now());
            wo.setStatus(TicketStatus.WAITING_APPROVAL);
            wo.setCurrentStage("waiting_approval");
            recordHistory(wo, prev, TicketStatus.WAITING_APPROVAL, actor, "Sent for approval");
            notificationService.notifyApprover(wo);
        }
        workOrderRepo.save(wo);
        return mapper.toResponse(wo);
    }

    // ── Approval decision ─────────────────────────────────

    public WorkOrderResponse approvalDecision(UUID id, ApprovalDecision decision,
                                               String note, User actor) {
        WorkOrder wo = getActiveWorkOrder(id, actor);
        if (wo.getStatus() != TicketStatus.WAITING_APPROVAL) {
            throw new WorkflowException("Ticket not in waiting_approval status");
        }
        TicketStatus prev = wo.getStatus();
        wo.setApprovalDecision(decision);
        wo.setApprovedBy(actor);

        if (decision == ApprovalDecision.approved || decision == ApprovalDecision.purchase_request) {
            wo.setStatus(TicketStatus.IN_PROGRESS);
            wo.setCurrentStage("in_progress");
            recordHistory(wo, prev, TicketStatus.IN_PROGRESS, actor, note);
        } else {
            wo.setStatus(TicketStatus.WAITING_MATERIALS);
            wo.setCurrentStage("waiting_materials");
            wo.setRejectionReason(note);
            recordHistory(wo, prev, TicketStatus.WAITING_MATERIALS, actor, "Rejected: " + note);
            notificationService.notifyUser(wo.getAssignedTo(), wo, "REJECTED", "Approval rejected");
        }
        workOrderRepo.save(wo);
        return mapper.toResponse(wo);
    }

    // ── Close / Reopen ────────────────────────────────────

    public WorkOrderResponse close(UUID id, String note, User actor) {
        WorkOrder wo = getActiveWorkOrder(id, actor);
        workflowEngine.validate(wo.getStatus(), TicketStatus.CLOSED, actor.getRole());
        TicketStatus prev = wo.getStatus();
        wo.setStatus(TicketStatus.CLOSED);
        wo.setCurrentStage("closed");
        wo.setClosedAt(Instant.now());
        workOrderRepo.save(wo);
        recordHistory(wo, prev, TicketStatus.CLOSED, actor, note);
        return mapper.toResponse(wo);
    }

    public WorkOrderResponse reopen(UUID id, String note, User actor) {
        WorkOrder wo = getActiveWorkOrder(id, actor);
        if (wo.getStatus() != TicketStatus.COMPLETED) {
            throw new WorkflowException("Only completed tickets can be reopened");
        }
        TicketStatus prev = wo.getStatus();
        wo.setStatus(TicketStatus.IN_PROGRESS);
        wo.setCurrentStage("in_progress");
        wo.setReopenedCount(wo.getReopenedCount() + 1);
        wo.setCompletedDate(null);
        workOrderRepo.save(wo);
        recordHistory(wo, prev, TicketStatus.IN_PROGRESS, actor, "Reopened: " + note);
        return mapper.toResponse(wo);
    }

    // ── Lock / Unlock ─────────────────────────────────────

    /**
     * Roles that work tickets through the workflow (WorkflowEngine /transition roles that act on
     * the ticket lifecycle). Residents/departments/others only submit and view, so they may not lock.
     */
    private static final java.util.Set<UserRole> LOCK_ROLES = java.util.Set.of(
        UserRole.control_room, UserRole.technician, UserRole.hospital_supervisor,
        UserRole.region_supervisor, UserRole.system_admin, UserRole.maintenance_manager);

    private void assertCanLock(User actor) {
        if (!LOCK_ROLES.contains(actor.getRole())) {
            throw new AccessDeniedException("Role not permitted to lock/unlock tickets");
        }
    }

    public void lock(UUID id, User actor) {
        assertCanLock(actor);
        // getActiveWorkOrder = WorkOrderAccessPolicy.requireReadable (tenant + role scope, 404 otherwise)
        WorkOrder wo = getActiveWorkOrder(id, actor);
        if (wo.isLocked()) throw new TicketLockedException("Already locked");
        wo.setLocked(true);
        wo.setLockedBy(actor);
        wo.setLockedAt(Instant.now());
        workOrderRepo.save(wo);
    }

    public void unlock(UUID id, User actor) {
        assertCanLock(actor);
        WorkOrder wo = getActiveWorkOrder(id, actor);
        if (!wo.isLocked()) return;
        if (!wo.getLockedBy().getId().equals(actor.getId()) &&
            actor.getRole() != UserRole.system_admin) {
            throw new AccessDeniedException("Cannot unlock another user's lock");
        }
        wo.setLocked(false);
        wo.setLockedBy(null);
        wo.setLockedAt(null);
        workOrderRepo.save(wo);
    }

    // ── Get by ID ─────────────────────────────────────────

    @Transactional(readOnly = true)
    public WorkOrderResponse getById(UUID id, User actor) {
        return mapper.toResponse(getActiveWorkOrder(id, actor));
    }

    // ── Query ─────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Page<WorkOrderResponse> search(UUID centerId, UUID controlRoomId,
                                           TicketStatus status, TicketPriority priority,
                                           UUID assignedToId, Pageable pageable,
                                           User actor) {
        WorkOrderScope scope = accessPolicy.resolveScope(actor);
        // No visibility at all: empty page without touching the database
        if (scope.isNone()) return Page.empty(pageable);

        UUID effectiveAssignedTo = tenantSecurity.resolveAssignedToFilter(actor, assignedToId);
        // Caller filters are ANDed after the scope predicate, so they can only narrow the result
        Specification<WorkOrder> filters = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (centerId     != null) predicates.add(cb.equal(root.get("center").get("id"),      centerId));
            if (controlRoomId!= null) predicates.add(cb.equal(root.get("controlRoom").get("id"), controlRoomId));
            if (status       != null) predicates.add(cb.equal(root.get("status"),                status));
            if (priority     != null) predicates.add(cb.equal(root.get("priority"),              priority));
            if (effectiveAssignedTo != null) {
                predicates.add(cb.equal(root.get("assignedTo").get("id"), effectiveAssignedTo));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
        Specification<WorkOrder> spec = accessPolicy.specFor(scope).and(filters);
        return workOrderRepo.findAll(spec, pageable)
            .map(mapper::toResponse);
    }

    @Transactional(readOnly = true)
    public List<TicketHistoryResponse> getHistory(UUID id, User actor) {
        getActiveWorkOrder(id, actor);
        return historyRepo.findByWorkOrderIdOrderByCreatedAtAsc(id)
            .stream()
            .map(h -> TicketHistoryResponse.builder()
                .id(h.getId())
                .fromStatus(h.getFromStatus())
                .toStatus(h.getToStatus())
                .stage(h.getStage())
                .note(h.getNote())
                .createdAt(h.getCreatedAt())
                .actor(h.getActor() == null ? null : TicketHistoryResponse.ActorSummary.builder()
                    .id(h.getActor().getId())
                    .fullName(h.getActor().getFullName())
                    .role(h.getActor().getRole())
                    .build())
                .build())
            .toList();
    }

    // ── Helpers ───────────────────────────────────────────

    private WorkOrder getActiveWorkOrder(UUID id, User actor) {
        return accessPolicy.requireReadable(id, actor);
    }

    private void recordHistory(WorkOrder wo, TicketStatus from, TicketStatus to,
                                User actor, String note) {
        historyRepo.save(TicketHistory.builder()
            .workOrder(wo)
            .actor(actor)
            .actorRole(actor.getRole())
            .fromStatus(from)
            .toStatus(to)
            .stage(wo.getCurrentStage())
            .note(InputSanitizer.clean(note, 1000))
            .build());
    }

    private void applyTransitionSideEffects(WorkOrder wo, TransitionRequest req,
                                             User actor, TicketStatus to) {
        switch (to) {
            case IN_PROGRESS -> {
                if (req.getScheduledDate() != null) wo.setScheduledDate(req.getScheduledDate());
            }
            case WAITING_MATERIALS -> {
                wo.setNeedsMaterials(true);
                wo.setMaterialRequiredAt(Instant.now());
                wo.setTechReport(req.getNote());
            }
            case COMPLETED -> {
                wo.setCompletedDate(Instant.now());
                wo.setTechReport(req.getNote());
            }
            case CANCELLED -> wo.setRejectionReason(req.getNote());
            default -> {}
        }
    }

    private void validateSubmitterRole(UserRole role, ControlRoomType crType) {
        if (role == UserRole.system_admin || role == UserRole.hospital_supervisor
                || role == UserRole.maintenance_manager) return;
        boolean valid = switch (crType) {
            case residential -> role == UserRole.resident    || role == UserRole.control_room;
            case hospital    -> role == UserRole.department  || role == UserRole.control_room;
            case it, medical -> role == UserRole.department  || role == UserRole.control_room;
        };
        if (!valid) throw new AccessDeniedException(
            "Role [" + role + "] cannot submit to [" + crType + "] control room");
    }
}
