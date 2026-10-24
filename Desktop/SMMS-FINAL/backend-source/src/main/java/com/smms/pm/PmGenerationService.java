package com.smms.pm;

import com.smms.entity.Asset;
import com.smms.entity.User;
import com.smms.entity.WorkOrder;
import com.smms.enums.UserRole;
import com.smms.exception.ConflictException;
import com.smms.exception.PlanLimitExceededException;
import com.smms.exception.ResourceNotFoundException;
import com.smms.exception.WorkflowException;
import com.smms.repository.AssetRepository;
import com.smms.repository.UserRepository;
import com.smms.repository.WorkOrderRepository;
import com.smms.service.NotificationService;
import com.smms.service.WorkOrderService;
import com.smms.workflow.GeneratedWorkOrderSpec;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.*;

/**
 * Generates preventive-maintenance work orders. Not transactional itself: every plan runs in its own
 * REQUIRES_NEW transaction (row lock SKIP LOCKED, run claim guarded by UNIQUE(plan, due date),
 * work order through WorkOrderService.createGenerated, next-due advance) so one failing plan never
 * blocks the rest and a failure leaves no half-created state. Runs without a TenantContext: every
 * lookup names the plan's tenant explicitly.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PmGenerationService {

    public enum Outcome { GENERATED, NOT_DUE, LOCKED, ALREADY_GENERATED, INACTIVE, FAILED }

    public record Result(Outcome outcome, UUID planId, LocalDate dueDate, UUID workOrderId, String error) {}

    public record Summary(int considered, int generated, int failed, int skipped) {}

    private final MaintenancePlanRepository planRepo;
    private final MaintenancePlanRunRepository runRepo;
    private final AssetRepository assetRepo;
    private final UserRepository userRepo;
    private final WorkOrderRepository workOrderRepo;
    private final WorkOrderService workOrderService;
    private final NotificationService notificationService;
    private final PlatformTransactionManager txManager;
    private final PmProperties props;

    private TransactionTemplate requiresNew() {
        TransactionTemplate t = new TransactionTemplate(txManager);
        t.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return t;
    }

    /** Scheduler entry: one pass over the plans that reached their lead window. */
    public Summary runDue(LocalDate today) {
        List<Object[]> refs = planRepo.findDueRefs(today, Math.max(1, props.getBatchSize()));
        int generated = 0, failed = 0, skipped = 0;
        for (Object[] ref : refs) {
            UUID planId = UUID.fromString((String) ref[0]);
            UUID tenantId = UUID.fromString((String) ref[1]);
            Result r = generate(planId, tenantId, today, false);
            switch (r.outcome()) {
                case GENERATED -> generated++;
                case FAILED -> failed++;
                default -> skipped++;
            }
        }
        if (!refs.isEmpty()) {
            log.info("PM generation pass: considered={} generated={} failed={} skipped={}",
                refs.size(), generated, failed, skipped);
        }
        return new Summary(refs.size(), generated, failed, skipped);
    }

    /** Scheduler semantics: a failure is recorded and returned, never thrown. */
    public Result generate(UUID planId, UUID tenantId, LocalDate today, boolean manual) {
        if (manual) return requiresNew().execute(s -> generateInTx(planId, tenantId, today, true));
        LocalDate dueDate = null;
        try {
            Result r = requiresNew().execute(s -> generateInTx(planId, tenantId, today, false));
            return r;
        } catch (RuntimeException e) {
            String msg = sanitize(e);
            try {
                dueDate = requiresNew().execute(s -> recordFailure(planId, tenantId, msg));
            } catch (RuntimeException inner) {
                log.error("PM failure could not be recorded: plan={} cause={}", planId, inner.getClass().getSimpleName());
            }
            log.warn("PM generation failed: plan={} tenant={} reason={}", planId, tenantId, msg);
            return new Result(Outcome.FAILED, planId, dueDate, null, msg);
        }
    }

    private Result generateInTx(UUID planId, UUID tenantId, LocalDate today, boolean manual) {
        Optional<MaintenancePlan> locked = manual
            ? planRepo.lockWaiting(planId, tenantId) : planRepo.lockForGeneration(planId, tenantId);
        if (locked.isEmpty()) {
            if (manual) throw new ResourceNotFoundException("Plan not found");
            return new Result(Outcome.LOCKED, planId, null, null, null);
        }
        MaintenancePlan plan = locked.get();
        LocalDate due = plan.getNextDueDate();
        if (!plan.isActive()) {
            if (manual) throw new WorkflowException("The plan is inactive");
            return new Result(Outcome.INACTIVE, planId, due, null, null);
        }
        if (!manual && !PmSchedule.isDue(due, plan.getLeadDays(), today)) {
            return new Result(Outcome.NOT_DUE, planId, due, null, null);
        }
        int claimed = runRepo.claim(UUID.randomUUID(), tenantId, planId, due, manual ? "manual" : "scheduler");
        if (claimed == 0) {
            if (manual) throw new ConflictException("A work order was already generated for " + due);
            // the period is already covered (plan date moved back): step over it so the plan is not stuck
            advance(plan);
            return new Result(Outcome.ALREADY_GENERATED, planId, due, null, null);
        }
        Asset asset = assetRepo.findByIdAndTenantIdAndDeletedAtIsNull(plan.getAsset().getId(), tenantId)
            .orElseThrow(() -> new ResourceNotFoundException("Asset not found"));
        if (asset.isDecommissioned()) throw new WorkflowException("The asset is decommissioned");
        User owner = userRepo.findByIdAndTenantIdAndDeletedAtIsNull(plan.getOwner().getId(), tenantId)
            .orElseThrow(() -> new WorkflowException("The plan owner no longer exists"));

        String description = (plan.getInstructions() == null ? "" : plan.getInstructions() + "\n\n")
            + "Preventive maintenance due " + due;
        WorkOrder wo = workOrderService.createGenerated(new GeneratedWorkOrderSpec(
            tenantId, plan.getControlRoom().getId(), plan.getSubType(), plan.getPriority(),
            "PM: " + plan.getTitle() + " - " + asset.getName(), description, asset.getLocation(), asset.getId(),
            owner, "Generated by preventive maintenance plan '" + plan.getTitle() + "' (due " + due + ")"));
        runRepo.attachWorkOrder(tenantId, planId, due, wo.getId());
        advance(plan);
        log.info("AUDIT pm work order generated: plan={} due={} workOrder={} manual={}", planId, due, wo.getId(), manual);
        return new Result(Outcome.GENERATED, planId, due, wo.getId(), null);
    }

    private void advance(MaintenancePlan plan) {
        plan.setNextDueDate(PmSchedule.nextAfter(plan.getStartDate(), plan.getFrequencyUnit(),
            plan.getFrequencyInterval(), plan.getNextDueDate()));
        planRepo.saveAndFlush(plan);
    }

    /** Second transaction after a failed generation: failed run row + one notification to the center's managers. */
    private LocalDate recordFailure(UUID planId, UUID tenantId, String message) {
        MaintenancePlan plan = planRepo.findByIdAndTenantIdAndDeletedAtIsNull(planId, tenantId).orElse(null);
        if (plan == null) return null;
        LocalDate due = plan.getNextDueDate();
        int inserted = runRepo.insertFailed(UUID.randomUUID(), tenantId, planId, due, "scheduler", message);
        if (inserted == 0) {
            runRepo.updateFailedError(tenantId, planId, due, message);
            return due; // already notified for this period
        }
        String title = "PM generation failed: " + plan.getTitle() + " (due " + due + ")";
        for (User u : userRepo.findAllByCenterIdAndRoleAndDeletedAtIsNull(plan.getCenter().getId(),
                UserRole.maintenance_manager)) {
            if (u.isActive() && tenantId.equals(u.getTenantId())) {
                notificationService.notifyUser(u, null, "PM_FAILED", truncate(title, 300));
            }
        }
        return due;
    }

    /** One notification per overdue run (guarded by overdue_notified_at); returns how many were sent. */
    public int notifyOverdue(LocalDate today) {
        int sent = 0;
        for (UUID runId : runRepo.findOverdueUnnotified(today, Math.max(1, props.getOverdueBatchSize()))) {
            try {
                Boolean ok = requiresNew().execute(s -> notifyOverdueRun(runId));
                if (Boolean.TRUE.equals(ok)) sent++;
            } catch (RuntimeException e) {
                log.warn("PM overdue notification failed: run={} cause={}", runId, e.getClass().getSimpleName());
            }
        }
        return sent;
    }

    private boolean notifyOverdueRun(UUID runId) {
        if (runRepo.markOverdueNotified(runId) == 0) return false; // another pass got it
        MaintenancePlanRun run = runRepo.findById(runId).orElseThrow();
        MaintenancePlan plan = planRepo.findByIdAndTenantIdAndDeletedAtIsNull(run.getPlanId(), run.getTenantId()).orElse(null);
        WorkOrder wo = run.getWorkOrderId() == null ? null : workOrderRepo.findById(run.getWorkOrderId()).orElse(null);
        if (plan == null || wo == null || !run.getTenantId().equals(wo.getTenantId())) return true;
        String title = truncate("PM overdue: " + wo.getTicketNumber() + " (due " + run.getDueDate() + ")", 300);
        Set<UUID> told = new HashSet<>();
        List<User> recipients = new ArrayList<>(userRepo.findAllByCenterIdAndRoleAndDeletedAtIsNull(
            plan.getCenter().getId(), UserRole.maintenance_manager));
        recipients.add(plan.getOwner());
        for (User u : recipients) {
            if (u.isActive() && u.getDeletedAt() == null && run.getTenantId().equals(u.getTenantId()) && told.add(u.getId())) {
                notificationService.notifyUser(u, wo, "PM_OVERDUE", title);
            }
        }
        return true;
    }

    static String sanitize(Throwable e) {
        String msg;
        if (e instanceof WorkflowException || e instanceof ResourceNotFoundException
            || e instanceof IllegalArgumentException || e instanceof PlanLimitExceededException
            || e instanceof ConflictException) {
            msg = e.getMessage();
        } else {
            msg = "Unexpected error (" + e.getClass().getSimpleName() + "); see the server log";
        }
        if (msg == null) msg = e.getClass().getSimpleName();
        msg = msg.replaceAll("[\\p{Cntrl}]", " ").trim();
        return truncate(msg, 500);
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
