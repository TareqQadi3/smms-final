package com.smms.config;

import com.smms.enums.PlanType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for static plan limits configuration.
 */
class PlanConfigTest {

    @Test
    void freePlanLimits() {
        PlanConfig.PlanLimits limits = PlanConfig.get(PlanType.FREE);
        assertThat(limits.maxUsers()).isEqualTo(10);
        assertThat(limits.maxTickets()).isEqualTo(100);
        assertThat(limits.maxCenters()).isEqualTo(1);
        assertThat(limits.isUnlimited()).isFalse();
    }

    @Test
    void proPlanLimits() {
        PlanConfig.PlanLimits limits = PlanConfig.get(PlanType.PRO);
        assertThat(limits.maxUsers()).isEqualTo(100);
        assertThat(limits.maxTickets()).isEqualTo(5_000);
        assertThat(limits.maxCenters()).isEqualTo(10);
        assertThat(limits.isUnlimited()).isFalse();
    }

    @Test
    void enterprisePlanIsUnlimited() {
        PlanConfig.PlanLimits limits = PlanConfig.get(PlanType.ENTERPRISE);
        assertThat(limits.isUnlimited()).isTrue();
        assertThat(limits.maxUsers()).isEqualTo(Integer.MAX_VALUE);
    }

    @ParameterizedTest(name = "plan {0} is defined")
    @EnumSource(PlanType.class)
    void allPlansAreDefined(PlanType plan) {
        assertThat(PlanConfig.get(plan)).isNotNull();
    }

    @Test
    void proLimitsExceedFreeLimits() {
        assertThat(PlanConfig.get(PlanType.PRO).maxUsers())
            .isGreaterThan(PlanConfig.get(PlanType.FREE).maxUsers());
        assertThat(PlanConfig.get(PlanType.PRO).maxTickets())
            .isGreaterThan(PlanConfig.get(PlanType.FREE).maxTickets());
        assertThat(PlanConfig.get(PlanType.PRO).maxCenters())
            .isGreaterThan(PlanConfig.get(PlanType.FREE).maxCenters());
    }
}
