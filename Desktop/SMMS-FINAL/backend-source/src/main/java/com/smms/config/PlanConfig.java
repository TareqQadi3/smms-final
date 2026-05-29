package com.smms.config;

import com.smms.enums.PlanType;

import java.util.Map;

/**
 * Static plan limits configuration.
 * Change here to update plan caps without any DB migration.
 */
public final class PlanConfig {

    private PlanConfig() {}

    public record PlanLimits(int maxUsers, int maxTickets, int maxCenters) {
        public static final int UNLIMITED = Integer.MAX_VALUE;
        public boolean isUnlimited() { return maxUsers == UNLIMITED; }
    }

    public static final Map<PlanType, PlanLimits> LIMITS = Map.of(
        PlanType.FREE,       new PlanLimits(10,          100,   1),
        PlanType.PRO,        new PlanLimits(100,          5_000, 10),
        PlanType.ENTERPRISE, new PlanLimits(PlanLimits.UNLIMITED, PlanLimits.UNLIMITED, PlanLimits.UNLIMITED)
    );

    public static PlanLimits get(PlanType plan) {
        return LIMITS.getOrDefault(plan, LIMITS.get(PlanType.FREE));
    }
}
