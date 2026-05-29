package com.smms.service;

import com.smms.cache.SubscriptionCache;
import com.smms.config.PlanConfig;
import com.smms.entity.TenantSubscription;
import com.smms.enums.PlanType;
import com.smms.enums.TenantStatus;
import com.smms.exception.ResourceNotFoundException;
import com.smms.repository.TenantSubscriptionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional
public class SubscriptionService {

    private final TenantSubscriptionRepository repo;
    private final SubscriptionCache cache;

    // ── Read ──────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public TenantSubscription get(UUID tenantId) {
        return repo.findByTenantId(tenantId)
            .orElseThrow(() -> new ResourceNotFoundException("No subscription found for tenant: " + tenantId));
    }

    // ── Create (called when provisioning a new tenant) ────────

    /**
     * Creates a default FREE subscription.
     * Start date = now, end date = 30 days (trial).
     */
    public TenantSubscription createDefault(UUID tenantId) {
        return repo.save(TenantSubscription.builder()
            .tenantId(tenantId)
            .planType(PlanType.FREE)
            .status(TenantStatus.ACTIVE)
            .startDate(Instant.now())
            .endDate(Instant.now().plus(30, ChronoUnit.DAYS)) // 30-day trial
            .notes("Default trial subscription")
            .build());
    }

    /**
     * Creates an ENTERPRISE subscription (for existing / default tenants).
     * No end date — lifetime plan.
     */
    public TenantSubscription createEnterprise(UUID tenantId) {
        return repo.save(TenantSubscription.builder()
            .tenantId(tenantId)
            .planType(PlanType.ENTERPRISE)
            .status(TenantStatus.ACTIVE)
            .startDate(Instant.now())
            .endDate(null)  // lifetime
            .notes("Enterprise plan — no expiry")
            .build());
    }

    // ── Lifecycle ────────────────────────────────────────────

    public TenantSubscription suspend(UUID tenantId, String reason) {
        TenantSubscription sub = get(tenantId);
        sub.setStatus(TenantStatus.SUSPENDED);
        sub.setNotes(reason);
        TenantSubscription saved = repo.save(sub);
        cache.invalidate(tenantId);
        return saved;
    }

    public TenantSubscription activate(UUID tenantId) {
        TenantSubscription sub = get(tenantId);
        sub.setStatus(TenantStatus.ACTIVE);
        TenantSubscription saved = repo.save(sub);
        cache.invalidate(tenantId);
        return saved;
    }

    // ── Plan change ───────────────────────────────────────────

    public TenantSubscription changePlan(UUID tenantId, PlanType newPlan, Integer durationDays) {
        TenantSubscription sub = get(tenantId);
        sub.setPlanType(newPlan);
        sub.setStatus(TenantStatus.ACTIVE);
        sub.setStartDate(Instant.now());
        sub.setEndDate(durationDays != null
            ? Instant.now().plus(durationDays, ChronoUnit.DAYS)
            : null);
        sub.setNotes("Plan changed to " + newPlan);
        TenantSubscription saved = repo.save(sub);
        cache.invalidate(tenantId);
        return saved;
    }

    // ── Usage + limits ────────────────────────────────────────

    @Transactional(readOnly = true)
    public SubscriptionSummary getSummary(UUID tenantId,
                                          PlanEnforcementService enforcement) {
        TenantSubscription sub = get(tenantId);
        PlanConfig.PlanLimits limits = PlanConfig.get(sub.getPlanType());
        PlanEnforcementService.UsageMetrics usage = enforcement.getUsage(tenantId);
        return new SubscriptionSummary(sub, limits, usage);
    }

    public record SubscriptionSummary(
        TenantSubscription subscription,
        PlanConfig.PlanLimits limits,
        PlanEnforcementService.UsageMetrics usage
    ) {}
}
