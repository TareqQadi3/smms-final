package com.smms.service;

import com.smms.dto.request.CreateWorkOrderRequest;
import com.smms.dto.request.TransitionRequest;
import com.smms.dto.response.WorkOrderResponse;
import com.smms.entity.*;
import com.smms.enums.*;
import com.smms.exception.*;
import com.smms.mapper.WorkOrderMapper;
import com.smms.repository.*;
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
    private final UserRepository userRepo;
    private final ControlRoomRepository controlRoomRepo;
    private final WorkflowEngine workflowEngine;
    private final NotificationService notificationService;
    private final WorkOrderMapper mapper;

    // ── Create ────────────────────────────────────────────

    public WorkOrderResponse create(CreateWorkOrderRequest req, User actor) {
        ControlRoom cr = controlRoomRepo.findById(req.getControlRoomId())
            .orElseThrow(() -> new ResourceNotFoundException("ControlRoom not found"));

        validateSubmitterRole(actor.getRole(), cr.getType());

        WorkOrder wo = WorkOrder.builder()
            .center(cr.getCenter())
            .controlRoom(cr)
            .subType(req.getSubType())
            .title(req.getTitle())
            .description(req.getDescription())
            .locationDetail(req.getLocationDetail())
            .priority(req.getPriority())
            .requestedBy(actor)
            .createdByRole(actor.getRole())
            .status(TicketStatus.NEW)
            .currentStage("new")
            .build();

        WorkOrder saved = workOrderRepo.save(wo);
        recordHistory(saved, null, TicketStatus.NEW, actor, "Ticket created");
        notificationService.notifyControlRoom(saved);
        return mapper.toResponse(saved);
    }

    // ── Transition ────────────────────────────────────────

    public WorkOrderResponse transition(UUID id, TransitionRequest req, User actor) {
        WorkOrder wo = getActiveWorkOrder(id);

        if (wo.isLocked() && !wo.getLockedBy().getId().equals(actor.getId())) {
            throw new TicketLockedException("Ticket is locked by another user");
        }

        TicketStatus from = wo.getStatus();
        TicketStatus to   = req.getTargetStatus();

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

    // ── Assign technician ─────────────────────────────────

    public WorkOrderResponse assign(UUID id, UUID technicianId, String note, User actor) {
        WorkOrder wo = getActiveWorkOrder(id);
        if (wo.getStatus() != TicketStatus.UNDER_REVIEW) {
            throw new WorkflowException("Can only assign from under_review status");
        }
        User tech = userRepo.findById(technicianId)
            .filter(u -> u.getRole() == UserRole.technician)
            .orElseThrow(() -> new ResourceNotFoundException("Technician not found"));

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
        WorkOrder wo = getActiveWorkOrder(id);
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
        WorkOrder wo = getActiveWorkOrder(id);
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
        WorkOrder wo = getActiveWorkOrder(id);
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
        WorkOrder wo = getActiveWorkOrder(id);
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

    public void lock(UUID id, User actor) {
        WorkOrder wo = getActiveWorkOrder(id);
        if (wo.isLocked()) throw new TicketLockedException("Already locked");
        wo.setLocked(true);
        wo.setLockedBy(actor);
        wo.setLockedAt(Instant.now());
        workOrderRepo.save(wo);
    }

    public void unlock(UUID id, User actor) {
        WorkOrder wo = getActiveWorkOrder(id);
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
    public WorkOrderResponse getById(UUID id) {
        return mapper.toResponse(getActiveWorkOrder(id));
    }

    // ── Query ─────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Page<WorkOrderResponse> search(UUID centerId, UUID controlRoomId,
                                           TicketStatus status, TicketPriority priority,
                                           UUID assignedToId, int page, int size) {
        Specification<WorkOrder> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.isNull(root.get("deletedAt")));
            if (centerId     != null) predicates.add(cb.equal(root.get("center").get("id"),      centerId));
            if (controlRoomId!= null) predicates.add(cb.equal(root.get("controlRoom").get("id"), controlRoomId));
            if (status       != null) predicates.add(cb.equal(root.get("status"),                status));
            if (priority     != null) predicates.add(cb.equal(root.get("priority"),              priority));
            if (assignedToId != null) predicates.add(cb.equal(root.get("assignedTo").get("id"),  assignedToId));
            query.orderBy(cb.desc(root.get("createdAt")));
            return cb.and(predicates.toArray(new Predicate[0]));
        };
        return workOrderRepo.findAll(spec, PageRequest.of(page, size))
            .map(mapper::toResponse);
    }

    @Transactional(readOnly = true)
    public List<TicketHistory> getHistory(UUID id) {
        getActiveWorkOrder(id);
        return historyRepo.findByWorkOrderIdOrderByCreatedAtAsc(id);
    }

    // ── Helpers ───────────────────────────────────────────

    private WorkOrder getActiveWorkOrder(UUID id) {
        return workOrderRepo.findById(id)
            .filter(w -> w.getDeletedAt() == null)
            .orElseThrow(() -> new ResourceNotFoundException("WorkOrder not found: " + id));
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
            .note(note)
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
        boolean valid = switch (crType) {
            case residential -> role == UserRole.resident    || role == UserRole.control_room;
            case hospital    -> role == UserRole.department  || role == UserRole.control_room;
            case it, medical -> role == UserRole.department  || role == UserRole.control_room;
        };
        if (!valid) throw new AccessDeniedException(
            "Role [" + role + "] cannot submit to [" + crType + "] control room");
    }
}
