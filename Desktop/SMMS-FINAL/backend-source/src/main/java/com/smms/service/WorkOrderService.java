package com.smms.service;

import com.smms.dto.request.CreateWorkOrderRequest;
import com.smms.util.InputSanitizer;
import com.smms.dto.request.TransitionRequest;
import com.smms.dto.response.AssignableTechnicianResponse;
import com.smms.dto.response.DuplicateCandidateResponse;
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
import com.smms.workflow.GeneratedWorkOrderSpec;
import com.smms.workflow.WorkOrderHistoryWriter;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
    private final UserRepository userRepo;
    private final WorkOrderHistoryWriter historyWriter;
    private final ControlRoomRepository controlRoomRepo;
    private final AssetRepository assetRepo;

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
        WorkOrderResponse response = mapper.toResponse(saved);
        // Non-blocking hint; filtered by the creator's own visibility so it can never leak other tickets
        response.setPossibleDuplicates(findDuplicates(saved, actor));
        return response;
    }

    /**
     * System-generated work order (preventive maintenance) on behalf of {@code spec.requester()}.
     * Joins the caller's transaction; runs without a TenantContext (schedulers), so every lookup
     * names the tenant explicitly. The control room, the optional asset and the requester must all
     * belong to {@code spec.tenantId()} and the requester must be active; the tenant's ticket plan
     * limit applies. The row starts as a normal {@code new} ticket in the normal workflow.
     */
    public WorkOrder createGenerated(GeneratedWorkOrderSpec spec) {
        UUID tenantId = spec.tenantId();
        if (tenantId == null) throw new IllegalArgumentException("tenantId is required");
        if (spec.subType() == null) throw new IllegalArgumentException("subType is required");
        User requester = spec.requester();
        if (requester == null || !tenantId.equals(requester.getTenantId())) {
            throw new IllegalArgumentException("Requester does not belong to the tenant");
        }
        if (!requester.isActive() || requester.getDeletedAt() != null) {
            throw new WorkflowException("Requester is inactive");
        }
        ControlRoom cr = controlRoomRepo.findByIdAndTenantIdAndDeletedAtIsNull(spec.controlRoomId(), tenantId)
            .orElseThrow(() -> new ResourceNotFoundException("Control room not found"));
        if (!cr.isActive()) throw new WorkflowException("Control room is inactive");
        Asset asset = null;
        if (spec.assetId() != null) {
            asset = assetRepo.findByIdAndTenantIdAndDeletedAtIsNull(spec.assetId(), tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Asset not found"));
        }
        planEnforcement.checkTicketLimit(tenantId);

        WorkOrder wo = WorkOrder.builder()
            .center(cr.getCenter())
            .controlRoom(cr)
            .subType(spec.subType())
            .title(InputSanitizer.clean(spec.title(), 500))
            .description(InputSanitizer.clean(spec.description()))
            .locationDetail(InputSanitizer.clean(spec.locationDetail(), 300))
            .priority(spec.priority() != null ? spec.priority() : TicketPriority.medium)
            .requestedBy(requester)
            .createdByRole(requester.getRole())
            .asset(asset)
            .status(TicketStatus.NEW)
            .currentStage("new")
            .build();
        wo.setTenantId(tenantId);
        WorkOrder saved = workOrderRepo.save(wo);
        // ticket_number and sla_deadline come from DB triggers on INSERT (see create())
        workOrderRepo.flush();
        entityManager.refresh(saved);
        historyWriter.record(saved, null, TicketStatus.NEW, requester,
            spec.historyNote() != null ? spec.historyNote() : "Ticket created");
        notificationService.notifyControlRoom(saved);
        return saved;
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
            throw new WorkflowException(
                "Use POST /api/work-orders/{id}/assign to assign a technician; generic transition to assigned is not allowed");
        }
        if (from == TicketStatus.WAITING_APPROVAL && role != UserRole.system_admin) {
            throw new WorkflowException(
                "Use POST /api/work-orders/{id}/approval to decide on approval requests");
        }
        if (from == TicketStatus.COMPLETED && to == TicketStatus.IN_PROGRESS) {
            throw new WorkflowException(
                "Use POST /api/work-orders/{id}/reopen to reopen a completed ticket");
        }
    }

    // ── Return for information ────────────────────────────

    /** Stage label (not a status: no PG enum change) marking an under_review ticket as returned to the requester. */
    static final String STAGE_INFO_REQUESTED = "info_requested";

    private static final int DUPLICATE_MAX = 5;
    private static final int DUPLICATE_SCAN = 200;
    private static final long DUPLICATE_WINDOW_DAYS = 7;

    /**
     * control_room / maintenance_manager ask the requester for more detail. Allowed only while the
     * ticket is under_review and not already waiting; status stays under_review, stage becomes
     * info_requested. One history row (under_review to under_review) and one notification to the requester.
     * SLA is not paused (documented gap).
     */
    public WorkOrderResponse requestInfo(UUID id, String note, User actor) {
        WorkOrder wo = getActiveWorkOrder(id, actor);
        assertNotLockedByOther(wo, actor);
        if (wo.getStatus() != TicketStatus.UNDER_REVIEW) {
            throw new WorkflowException("Information can only be requested while the ticket is under_review");
        }
        if (STAGE_INFO_REQUESTED.equals(wo.getCurrentStage())) {
            throw new WorkflowException("Information has already been requested for this ticket");
        }
        wo.setCurrentStage(STAGE_INFO_REQUESTED);
        workOrderRepo.save(wo);
        recordHistory(wo, TicketStatus.UNDER_REVIEW, TicketStatus.UNDER_REVIEW, actor,
            "Information requested: " + note);
        if (wo.getRequestedBy() != null && !wo.getRequestedBy().getId().equals(actor.getId())) {
            notificationService.notifyUser(wo.getRequestedBy(), wo, "INFO_REQUESTED",
                "Information requested: " + wo.getTicketNumber());
        }
        return mapper.toResponse(wo);
    }

    /**
     * Only the original requester may answer, and only while the stage is info_requested. The stage
     * returns to under_review, one history row is written and the ticket's control-room operators
     * are notified.
     */
    public WorkOrderResponse provideInfo(UUID id, String note, User actor) {
        WorkOrder wo = getActiveWorkOrder(id, actor);
        if (wo.getRequestedBy() == null || !wo.getRequestedBy().getId().equals(actor.getId())) {
            throw new AccessDeniedException("Only the requester can provide the requested information");
        }
        assertNotLockedByOther(wo, actor);
        if (wo.getStatus() != TicketStatus.UNDER_REVIEW
                || !STAGE_INFO_REQUESTED.equals(wo.getCurrentStage())) {
            throw new WorkflowException("No information request is pending for this ticket");
        }
        wo.setCurrentStage(workflowEngine.resolveStage(TicketStatus.UNDER_REVIEW));
        workOrderRepo.save(wo);
        recordHistory(wo, TicketStatus.UNDER_REVIEW, TicketStatus.UNDER_REVIEW, actor,
            "Information provided: " + note);
        notificationService.notifyInfoProvided(wo);
        return mapper.toResponse(wo);
    }

    private void assertNotLockedByOther(WorkOrder wo, User actor) {
        if (wo.isLocked() && !wo.getLockedBy().getId().equals(actor.getId())) {
            throw new TicketLockedException("Ticket is locked by another user");
        }
    }

    // ── Duplicate candidates (read-only, non-blocking) ────

    @Transactional(readOnly = true)
    public List<DuplicateCandidateResponse> possibleDuplicates(UUID id, User actor) {
        return findDuplicates(getActiveWorkOrder(id, actor), actor);
    }

    /**
     * Candidates share control room and sub type with the base ticket, are still open (not closed or
     * cancelled), were created in the last 7 days, and have either the same requester or an equal
     * normalized location (lowercase, trimmed, collapsed whitespace; blank never matches).
     * Visibility comes from WorkOrderAccessPolicy for the CALLER, so an invisible ticket is never
     * returned and never counted. At most 5 are returned, newest first.
     */
    private List<DuplicateCandidateResponse> findDuplicates(WorkOrder base, User actor) {
        if (base.getControlRoom() == null) return List.of();
        Instant cutoff = Instant.now().minus(DUPLICATE_WINDOW_DAYS, ChronoUnit.DAYS);
        Specification<WorkOrder> spec = Specification.where(accessPolicy.readableBy(actor))
            .and((root, q, cb) -> cb.and(
                cb.equal(root.get("controlRoom").get("id"), base.getControlRoom().getId()),
                cb.equal(root.get("subType"), base.getSubType()),
                cb.notEqual(root.get("status"), TicketStatus.CLOSED),
                cb.notEqual(root.get("status"), TicketStatus.CANCELLED),
                cb.greaterThanOrEqualTo(root.get("createdAt"), cutoff),
                cb.notEqual(root.get("id"), base.getId())));
        String baseLocation = normalizeLocation(base.getLocationDetail());
        UUID baseRequester = base.getRequestedBy() != null ? base.getRequestedBy().getId() : null;
        return workOrderRepo.findAll(spec,
                PageRequest.of(0, DUPLICATE_SCAN, Sort.by(Sort.Direction.DESC, "createdAt")))
            .stream()
            .filter(c -> (baseRequester != null && c.getRequestedBy() != null
                    && baseRequester.equals(c.getRequestedBy().getId()))
                || (!baseLocation.isEmpty() && baseLocation.equals(normalizeLocation(c.getLocationDetail()))))
            .limit(DUPLICATE_MAX)
            .map(c -> DuplicateCandidateResponse.builder()
                .id(c.getId()).ticketNumber(c.getTicketNumber())
                .status(c.getStatus()).createdAt(c.getCreatedAt()).build())
            .toList();
    }

    static String normalizeLocation(String s) {
        return s == null ? "" : s.trim().toLowerCase().replaceAll("\\s+", " ");
    }

    // ── Assign technician ─────────────────────────────────

    public WorkOrderResponse assign(UUID id, UUID technicianId, String note, User actor) {
        WorkOrder wo = getActiveWorkOrder(id, actor);
        if (wo.getStatus() != TicketStatus.UNDER_REVIEW) {
            throw new WorkflowException("Can only assign from under_review status");
        }
        if (STAGE_INFO_REQUESTED.equals(wo.getCurrentStage())) {
            throw new WorkflowException("Ticket is waiting for information from the requester; it cannot be assigned yet");
        }
        User tech = tenantSecurity.requireUser(technicianId, UserRole.technician);
        // A technician can only be assigned inside the ticket's own center; otherwise behave as unknown
        if (!tech.isActive() || tech.getCenter() == null || wo.getCenter() == null
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

    /**
     * Technicians the actor may pick for this ticket: active technicians of the ticket's own center
     * and tenant. Visibility of the ticket itself is decided by WorkOrderAccessPolicy (404 otherwise),
     * so this does not need access to the general user list.
     */
    @Transactional(readOnly = true)
    public List<AssignableTechnicianResponse> assignableTechnicians(UUID id, User actor) {
        WorkOrder wo = getActiveWorkOrder(id, actor);
        if (wo.getCenter() == null) return List.of();
        return userRepo.findAllByCenterIdAndRoleAndDeletedAtIsNull(wo.getCenter().getId(), UserRole.technician)
            .stream()
            .filter(User::isActive)
            .filter(t -> java.util.Objects.equals(t.getTenantId(), wo.getTenantId()))
            .map(t -> AssignableTechnicianResponse.builder()
                .id(t.getId()).fullName(t.getFullName()).fullNameAr(t.getFullNameAr()).build())
            .toList();
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
        recordHistory(wo, wo.getStatus(), wo.getStatus(), actor, "locked");
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
        recordHistory(wo, wo.getStatus(), wo.getStatus(), actor, "unlocked");
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
        historyWriter.record(wo, from, to, actor, note);
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
