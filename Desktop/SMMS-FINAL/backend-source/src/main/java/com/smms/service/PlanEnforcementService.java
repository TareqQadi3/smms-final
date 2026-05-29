package com.smms.service;

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

    /** Call before creating a new user. */
    public void checkUserLimit() {
        UUID tenantId = TenantContext.get();
        if (tenantId == null) return; // platform admin bypass

        PlanType    plan   = TenantContext.getPlan();
        PlanLimits  limits = PlanConfig.get(plan);
        if (limits.isUnlimited()) return;

        long current = userRepo.countByTenantIdAndDeletedAtIsNull(tenantId);
        if (current >= limits.maxUsers()) {
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

    /** Call before creating a new center. */
    public void checkCenterLimit() {
        UUID tenantId = TenantContext.get();
        if (tenantId == null) return;

        PlanType   plan   = TenantContext.getPlan();
        PlanLimits limits = PlanConfig.get(plan);
        if (limits.isUnlimited()) return;

        long current = centerRepo.countByTenantIdAndDeletedAtIsNull(tenantId);
        if (current >= limits.maxCenters()) {
            throw new PlanLimitExceededException(
                "Center limit reached (" + limits.maxCenters() + ") for plan " + plan
                + ". Upgrade your plan to add more centers.");
        }
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
