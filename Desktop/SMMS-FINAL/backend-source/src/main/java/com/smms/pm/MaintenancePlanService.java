package com.smms.pm;

import com.smms.assets.AssetAccessPolicy;
import com.smms.assets.AssetService;
import com.smms.entity.Asset;
import com.smms.entity.ControlRoom;
import com.smms.entity.User;
import com.smms.enums.TicketPriority;
import com.smms.enums.TicketStatus;
import com.smms.enums.UserRole;
import com.smms.exception.ConflictException;
import com.smms.exception.ResourceNotFoundException;
import com.smms.exception.WorkflowException;
import com.smms.pm.PlanDtos.*;
import com.smms.repository.UserRepository;
import com.smms.security.TenantSecurityService;
import com.smms.util.InputSanitizer;
import com.smms.util.PageRequests;
import com.smms.util.TenantContext;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

/**
 * Maintenance plan CRUD. Read: system_admin (tenant), region_supervisor (region), hospital/maintenance/
 * electrical/operating-company managers and control room (own center). Write: system_admin,
 * hospital_supervisor, maintenance_manager, electrical_manager (own center). Out of scope = 404.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class MaintenancePlanService {

    static final Set<UserRole> READERS = Set.of(UserRole.system_admin, UserRole.region_supervisor,
        UserRole.hospital_supervisor, UserRole.maintenance_manager, UserRole.electrical_manager,
        UserRole.operating_company_manager, UserRole.control_room);
    private static final Set<UserRole> OWNER_ROLES = Set.of(UserRole.system_admin, UserRole.hospital_supervisor,
        UserRole.maintenance_manager, UserRole.electrical_manager, UserRole.control_room);
    private static final Map<String, String> SORT = Map.of("nextDueDate", "nextDueDate", "title", "title",
        "createdAt", "createdAt");

    private final MaintenancePlanRepository planRepo;
    private final MaintenancePlanRunRepository runRepo;
    private final AssetService assetService;
    private final AssetAccessPolicy assetAccess;
    private final TenantSecurityService tenantSecurity;
    private final UserRepository userRepo;
    private final PmGenerationService generation;
    private final PmProperties props;
    private final org.springframework.transaction.PlatformTransactionManager txManager;

    private LocalDate today() { return LocalDate.now(props.zoneId()); }

    // ── read ─────────────────────────────────────────────────

    Specification<MaintenancePlan> readable(User actor) {
        UUID tenantId = TenantContext.get();
        UUID center = actor != null && actor.getCenter() != null ? actor.getCenter().getId() : null;
        UUID region = actor != null && actor.getRegion() != null ? actor.getRegion().getId() : null;
        UserRole role = actor == null ? null : actor.getRole();
        return (root, q, cb) -> {
            if (role == null || !READERS.contains(role)) return cb.disjunction();
            List<Predicate> and = new ArrayList<>();
            and.add(cb.isNull(root.get("deletedAt")));
            if (tenantId != null) and.add(cb.equal(root.get("tenantId"), tenantId));
            switch (role) {
                case system_admin -> { }
                case region_supervisor -> and.add(region == null ? cb.disjunction()
                    : cb.equal(root.get("center").get("region").get("id"), region));
                default -> and.add(center == null ? cb.disjunction() : cb.equal(root.get("center").get("id"), center));
            }
            return cb.and(and.toArray(new Predicate[0]));
        };
    }

    /** Active plans past their due date, or with a generated run whose open work order is past due. */
    Specification<MaintenancePlan> overdue(LocalDate today) {
        return (root, q, cb) -> {
            Subquery<UUID> sq = q.subquery(UUID.class);
            Root<MaintenancePlanRun> r = sq.from(MaintenancePlanRun.class);
            Root<com.smms.entity.WorkOrder> w = sq.from(com.smms.entity.WorkOrder.class);
            sq.select(r.get("id")).where(
                cb.equal(r.get("planId"), root.get("id")),
                cb.equal(r.get("status"), RunStatus.generated),
                cb.lessThan(r.<LocalDate>get("dueDate"), today),
                cb.equal(w.get("id"), r.get("workOrderId")),
                cb.not(w.get("status").in(List.of(TicketStatus.COMPLETED, TicketStatus.CLOSED, TicketStatus.CANCELLED))));
            return cb.and(cb.isTrue(root.get("active")),
                cb.or(cb.lessThan(root.<LocalDate>get("nextDueDate"), today), cb.exists(sq)));
        };
    }

    @Transactional(readOnly = true)
    public Page<PlanResponse> list(UUID centerId, UUID assetId, Boolean active, boolean overdueOnly,
                                   int page, int size, List<String> sort, User actor) {
        Pageable pageable = PageRequests.of(page, size, sort, SORT, Sort.by("nextDueDate", "id"));
        LocalDate today = today();
        Specification<MaintenancePlan> spec = readable(actor).and((root, q, cb) -> {
            List<Predicate> p = new ArrayList<>();
            if (centerId != null) p.add(cb.equal(root.get("center").get("id"), centerId));
            if (assetId != null) p.add(cb.equal(root.get("asset").get("id"), assetId));
            if (active != null) p.add(cb.equal(root.get("active"), active));
            return cb.and(p.toArray(new Predicate[0]));
        });
        if (overdueOnly) spec = spec.and(overdue(today));
        return planRepo.findAll(spec, pageable).map(p -> toResponse(p, today, null));
    }

    @Transactional(readOnly = true)
    public PlanResponse get(UUID id, User actor) {
        MaintenancePlan p = requireReadable(id, actor);
        List<RunResponse> runs = runRepo.findTop10ByPlanIdAndTenantIdOrderByDueDateDesc(p.getId(), p.getTenantId())
            .stream().map(RunResponse::of).toList();
        return toResponse(p, today(), runs);
    }

    @Transactional(readOnly = true)
    public Page<RunResponse> runs(UUID id, int page, int size, User actor) {
        MaintenancePlan p = requireReadable(id, actor);
        Pageable pageable = PageRequests.of(page, size, null, Map.of(), Sort.by(Sort.Direction.DESC, "dueDate"));
        return runRepo.findByPlanIdAndTenantId(p.getId(), p.getTenantId(), pageable).map(RunResponse::of);
    }

    MaintenancePlan requireReadable(UUID id, User actor) {
        Specification<MaintenancePlan> byId = (root, q, cb) -> cb.equal(root.get("id"), id);
        return planRepo.findOne(readable(actor).and(byId))
            .orElseThrow(() -> new ResourceNotFoundException("Plan not found"));
    }

    private MaintenancePlan requireWritable(UUID id, User actor) {
        assetAccess.assertWriter(actor);
        MaintenancePlan p = requireReadable(id, actor);
        if (!assetAccess.centerWritable(actor, p.getCenter().getId())) throw new ResourceNotFoundException("Plan not found");
        return p;
    }

    // ── write ────────────────────────────────────────────────

    public PlanResponse create(CreatePlanRequest req, User actor) {
        assetAccess.assertWriter(actor);
        Asset asset = assetService.requireReadable(req.assetId(), actor);
        if (!assetAccess.centerWritable(actor, asset.getCenter().getId())) throw new ResourceNotFoundException("Asset not found");
        if (asset.isDecommissioned()) throw new WorkflowException("The asset is decommissioned");
        ControlRoom room = requireRoom(req.controlRoomId(), asset);
        User owner = resolveOwner(req.ownerId(), actor, asset);
        LocalDate today = today();
        if (req.startDate().isBefore(today)) throw new IllegalArgumentException("startDate cannot be in the past");

        MaintenancePlan p = new MaintenancePlan();
        p.setTenantId(asset.getTenantId());
        p.setCenter(asset.getCenter());
        p.setAsset(asset);
        p.setControlRoom(room);
        p.setSubType(req.subType());
        p.setPriority(req.priority() != null ? req.priority() : TicketPriority.medium);
        p.setTitle(InputSanitizer.clean(req.title().trim(), 300));
        p.setInstructions(req.instructions() == null || req.instructions().isBlank() ? null : InputSanitizer.clean(req.instructions()));
        p.setFrequencyUnit(req.frequencyUnit());
        p.setFrequencyInterval(req.frequencyInterval());
        p.setLeadDays(req.leadDays() == null ? 0 : req.leadDays());
        p.setStartDate(req.startDate());
        p.setNextDueDate(req.startDate());
        p.setOwner(owner);
        MaintenancePlan saved = planRepo.saveAndFlush(p);
        log.info("AUDIT pm plan created: plan={} asset={} actor={}", saved.getId(), asset.getId(), actor.getId());
        return toResponse(saved, today, List.of());
    }

    public PlanResponse update(UUID id, UpdatePlanRequest req, User actor) {
        MaintenancePlan p = requireWritable(id, actor);
        if (!Objects.equals(p.getVersion(), req.version())) {
            throw new ConflictException("The plan was modified concurrently, reload and retry");
        }
        Asset asset = p.getAsset();
        ControlRoom room = requireRoom(req.controlRoomId(), asset);
        LocalDate today = today();
        int interval = req.frequencyInterval();
        boolean freqChanged = req.frequencyUnit() != p.getFrequencyUnit() || interval != p.getFrequencyInterval();
        LocalDate newDue = req.nextDueDate();
        if (newDue != null && newDue.isBefore(today)) throw new IllegalArgumentException("nextDueDate cannot be in the past");
        if (freqChanged || (newDue != null && !newDue.equals(p.getNextDueDate()))) {
            LocalDate due = newDue != null ? newDue : p.getNextDueDate();
            // a changed series restarts on the next due date
            p.setStartDate(due);
            p.setNextDueDate(due);
        }
        p.setFrequencyUnit(req.frequencyUnit());
        p.setFrequencyInterval(interval);
        p.setControlRoom(room);
        p.setSubType(req.subType());
        p.setPriority(req.priority() != null ? req.priority() : p.getPriority());
        p.setTitle(InputSanitizer.clean(req.title().trim(), 300));
        p.setInstructions(req.instructions() == null || req.instructions().isBlank() ? null : InputSanitizer.clean(req.instructions()));
        p.setLeadDays(req.leadDays() == null ? p.getLeadDays() : req.leadDays());
        if (req.ownerId() != null && !req.ownerId().equals(p.getOwner().getId())) {
            p.setOwner(resolveOwner(req.ownerId(), actor, asset));
        }
        MaintenancePlan saved = planRepo.saveAndFlush(p);
        log.info("AUDIT pm plan updated: plan={} actor={}", id, actor.getId());
        return get(saved.getId(), actor);
    }

    public PlanResponse activate(UUID id, User actor) {
        MaintenancePlan p = requireWritable(id, actor);
        if (p.getAsset().isDecommissioned()) throw new WorkflowException("The asset is decommissioned");
        if (!p.isActive()) {
            p.setActive(true);
            // periods missed while paused are not back-filled: continue with the next occurrence
            LocalDate today = today();
            if (p.getNextDueDate().isBefore(today)) {
                p.setNextDueDate(PmSchedule.firstOnOrAfter(p.getStartDate(), p.getFrequencyUnit(),
                    p.getFrequencyInterval(), today));
            }
            planRepo.saveAndFlush(p);
            log.info("AUDIT pm plan activated: plan={} actor={}", id, actor.getId());
        }
        return get(id, actor);
    }

    public PlanResponse deactivate(UUID id, User actor) {
        MaintenancePlan p = requireWritable(id, actor);
        if (p.isActive()) {
            p.setActive(false);
            planRepo.saveAndFlush(p);
            log.info("AUDIT pm plan deactivated: plan={} actor={}", id, actor.getId());
        }
        return get(id, actor);
    }

    public void delete(UUID id, User actor) {
        MaintenancePlan p = requireWritable(id, actor);
        p.setActive(false);
        p.setDeletedAt(Instant.now());
        planRepo.saveAndFlush(p);
        log.info("AUDIT pm plan deleted: plan={} actor={}", id, actor.getId());
    }

    /** Generates the current due run now (even before its lead window); 409 when it already exists. */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    public GenerateResponse generateNow(UUID id, User actor) {
        MaintenancePlan p = requireWritableReadOnly(id, actor);
        PmGenerationService.Result r = generation.generate(p.getId(), p.getTenantId(), today(), true);
        LocalDate next = planRepo.findByIdAndTenantIdAndDeletedAtIsNull(p.getId(), p.getTenantId())
            .map(MaintenancePlan::getNextDueDate).orElse(null);
        return new GenerateResponse(p.getId(), r.dueDate(), r.workOrderId(), next);
    }

    private MaintenancePlan requireWritableReadOnly(UUID id, User actor) {
        // the lookup needs a session: run it in a short read-only transaction of its own
        return new org.springframework.transaction.support.TransactionTemplate(txManager)
            .execute(s -> requireWritable(id, actor));
    }

    // ── helpers ──────────────────────────────────────────────

    private ControlRoom requireRoom(UUID roomId, Asset asset) {
        ControlRoom room = tenantSecurity.requireControlRoom(roomId);
        if (!Objects.equals(room.getTenantId(), asset.getTenantId())) throw new ResourceNotFoundException("ControlRoom not found");
        if (!room.isActive()) throw new WorkflowException("The control room is inactive");
        if (room.getCenter() == null || !asset.getCenter().getId().equals(room.getCenter().getId())) {
            throw new IllegalArgumentException("The control room belongs to another center than the asset");
        }
        return room;
    }

    private User resolveOwner(UUID ownerId, User actor, Asset asset) {
        UUID id = ownerId != null ? ownerId : actor.getId();
        User owner = userRepo.findByIdAndTenantIdAndDeletedAtIsNull(id, asset.getTenantId())
            .orElseThrow(() -> new IllegalArgumentException("The plan owner must be an active user of the same tenant"));
        if (!owner.isActive() || owner.getRole() == null || !OWNER_ROLES.contains(owner.getRole())) {
            throw new IllegalArgumentException("The plan owner must be an active manager, supervisor or control room user");
        }
        boolean sameCenter = owner.getCenter() != null && asset.getCenter().getId().equals(owner.getCenter().getId());
        if (owner.getRole() != UserRole.system_admin && !sameCenter) {
            throw new IllegalArgumentException("The plan owner must belong to the asset's center");
        }
        return owner;
    }

    PlanResponse toResponse(MaintenancePlan p, LocalDate today, List<RunResponse> runs) {
        Asset a = p.getAsset();
        return new PlanResponse(p.getId(), a.getId(), a.getAssetCode(), a.getName(), p.getCenter().getId(),
            p.getControlRoom().getId(), p.getSubType(), p.getPriority(), p.getTitle(), p.getInstructions(),
            p.getFrequencyUnit(), p.getFrequencyInterval(), p.getLeadDays(), p.getStartDate(), p.getNextDueDate(),
            p.isActive(), p.getOwner().getId(), p.getOwner().getFullName(),
            p.isActive() && p.getNextDueDate().isBefore(today), p.getVersion(), runs);
    }
}
