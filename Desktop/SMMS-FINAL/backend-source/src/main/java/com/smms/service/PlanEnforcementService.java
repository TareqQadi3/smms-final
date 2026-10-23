package com.smms.service;

import com.smms.cache.SubscriptionCache;
import com.smms.config.PlanConfig;
import com.smms.config.PlanConfig.PlanLimits;
import com.smms.enums.PlanType;
import com.smms.exception.PlanLimitExceededException;
import com.smms.repository.CenterRepository;
import com.smms.repository.UserRepository;
import com.smms.repository.WorkOrderRepository;
import com.smms.util.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Enforces plan limits before critical create operations.
 * system_admin (null tenantId) bypasses all checks.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PlanEnforcementService {

    private final UserRepository     userRepo;
    private final WorkOrderRepository workOrderRepo;
    private final CenterRepository   centerRepo;
    private final SubscriptionCache  subscriptionCache;

    /** Call before creating a new user. */
    public void checkUserLimit() {
        checkUserLimit(TenantContext.get(), 1);
    }

    /**
     * Limit check for the tenant a user is actually being created in (the platform admin has no
     * tenant context, so the tenant is derived from the new row's parent). Counts {@code additional}
     * new users. A null tenant (platform-level record) is never limited.
     */
    public void checkUserLimit(UUID tenantId, int additional) {
        if (tenantId == null) return;

        PlanType    plan   = planFor(tenantId);
        PlanLimits  limits = PlanConfig.get(plan);
        if (limits.isUnlimited()) return;

        long current = userRepo.countByTenantIdAndDeletedAtIsNull(tenantId);
        if (current + additional > limits.maxUsers()) {
            throw new PlanLimitExceededException(
                "User limit reached (" + limits.maxUsers() + ") for plan " + plan
                + ". Upgrade your plan to add more users.");
        }
    }

    /** Call before creating a new work order / ticket. */
    public void checkTicketLimit() {
        UUID tenantId = TenantContext.get();
        if (tenantId == null) return;

        PlanType   plan   = TenantContext.getPlan();
        PlanLimits limits = PlanConfig.get(plan);
        if (limits.isUnlimited()) return;

        long current = workOrderRepo.countByTenantIdAndDeletedAtIsNull(tenantId);
        if (current >= limits.maxTickets()) {
            throw new PlanLimitExceededException(
                "Ticket limit reached (" + limits.maxTickets() + ") for plan " + plan
                + ". Upgrade your plan to submit more tickets.");
        }
    }

    /**
     * Ticket limit for an explicit tenant (system-generated tickets run without a request context).
     * A null tenant is never limited.
     */
    public void checkTicketLimit(UUID tenantId) {
        if (tenantId == null) return;

        PlanType   plan   = planFor(tenantId);
        PlanLimits limits = PlanConfig.get(plan);
        if (limits.isUnlimited()) return;

        long current = workOrderRepo.countByTenantIdAndDeletedAtIsNull(tenantId);
        if (current >= limits.maxTickets()) {
            throw new PlanLimitExceededException(
                "Ticket limit reached (" + limits.maxTickets() + ") for plan " + plan
                + ". Upgrade your plan to submit more tickets.");
        }
    }

    /** Call before creating a new center. */
    public void checkCenterLimit() {
        checkCenterLimit(TenantContext.get());
    }

    public void checkCenterLimit(UUID tenantId) {
        if (tenantId == null) return;

        PlanType   plan   = planFor(tenantId);
        PlanLimits limits = PlanConfig.get(plan);
        if (limits.isUnlimited()) return;

        long current = centerRepo.countByTenantIdAndDeletedAtIsNull(tenantId);
        if (current >= limits.maxCenters()) {
            throw new PlanLimitExceededException(
                "Center limit reached (" + limits.maxCenters() + ") for plan " + plan
                + ". Upgrade your plan to add more centers.");
        }
    }

    /** Plan of the request's own tenant comes from the context; any other tenant from its subscription. */
    private PlanType planFor(UUID tenantId) {
        if (tenantId.equals(TenantContext.get())) return TenantContext.getPlan();
        if (subscriptionCache == null) return PlanType.FREE;
        return subscriptionCache.get(tenantId)
            .map(sub -> sub.getPlanType())
            .orElse(PlanType.FREE); // same default as JwtAuthFilter for tenants without a subscription
    }

    /** Returns current usage metrics for a tenant. */
    public UsageMetrics getUsage(UUID tenantId) {
        long users   = tenantId != null ? userRepo.countByTenantIdAndDeletedAtIsNull(tenantId)      : userRepo.count();
        long tickets = tenantId != null ? workOrderRepo.countByTenantIdAndDeletedAtIsNull(tenantId) : workOrderRepo.count();
        long centers = tenantId != null ? centerRepo.countByTenantIdAndDeletedAtIsNull(tenantId)    : centerRepo.count();
        return new UsageMetrics(users, tickets, centers);
    }

    public record UsageMetrics(long users, long tickets, long centers) {}
}
