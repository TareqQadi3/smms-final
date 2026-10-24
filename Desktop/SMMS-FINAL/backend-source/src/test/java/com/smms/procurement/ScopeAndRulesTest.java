package com.smms.procurement;

import com.smms.entity.Center;
import com.smms.entity.Region;
import com.smms.entity.User;
import com.smms.enums.UserRole;
import com.smms.exception.ResourceNotFoundException;
import com.smms.support.Fixtures;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScopeAndRulesTest {

    private final UUID tenant = UUID.randomUUID();
    private final Region region = Fixtures.region(tenant);
    private final Center centerA = Fixtures.center(region, tenant);
    private final Center centerB = Fixtures.center(region, tenant);
    private final Center foreign = Fixtures.center(Fixtures.region(tenant), tenant);

    private User user(UserRole role, Center c) { return Fixtures.user(role, c, c == null ? region : null, tenant); }

    @Test
    void scopeFollowsTheRoleMatrix() {
        assertThat(CenterScope.of(user(UserRole.system_admin, centerA)).kind()).isEqualTo(CenterScope.Kind.ALL);
        CenterScope rs = CenterScope.of(user(UserRole.region_supervisor, null));
        assertThat(rs.kind()).isEqualTo(CenterScope.Kind.REGION);
        assertThat(rs.contains(centerA)).isTrue();
        assertThat(rs.contains(centerB)).isTrue();
        assertThat(rs.contains(foreign)).isFalse();
        for (UserRole r : new UserRole[] {UserRole.warehouse, UserRole.maintenance_manager, UserRole.hospital_supervisor,
                UserRole.operating_company_manager, UserRole.electrical_manager, UserRole.technician, UserRole.control_room}) {
            CenterScope s = CenterScope.of(user(r, centerA));
            assertThat(s.kind()).as(r.name()).isEqualTo(CenterScope.Kind.CENTER);
            assertThat(s.contains(centerA)).isTrue();
            assertThat(s.contains(centerB)).isFalse();
            assertThat(s.contains(foreign)).isFalse();
        }
    }

    @Test
    void usersWithoutLocationOrWithEndUserRolesSeeNothing() {
        for (UserRole r : new UserRole[] {UserRole.resident, UserRole.department, UserRole.other}) {
            assertThat(CenterScope.of(user(r, centerA)).kind()).as(r.name()).isEqualTo(CenterScope.Kind.NONE);
        }
        User noCenter = Fixtures.user(UserRole.warehouse, null, null, tenant);
        assertThat(CenterScope.of(noCenter).contains(centerA)).isFalse();
        User noRegion = Fixtures.user(UserRole.region_supervisor, null, null, tenant);
        assertThat(CenterScope.of(noRegion).kind()).isEqualTo(CenterScope.Kind.NONE);
        assertThat(CenterScope.of(null).kind()).isEqualTo(CenterScope.Kind.NONE);
        assertThatThrownBy(() -> CenterScope.of(user(UserRole.warehouse, centerA)).requireContains(centerB, "Center"))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void requireRoleIs403() {
        CenterScope.requireRole(user(UserRole.warehouse, centerA), UserRole.warehouse);
        assertThatThrownBy(() -> CenterScope.requireRole(user(UserRole.technician, centerA), UserRole.warehouse))
            .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> CenterScope.requireRole(null, UserRole.warehouse)).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void quantityRules() {
        assertThat(Quantities.require(new BigDecimal("1.250"), "q")).isEqualByComparingTo("1.25");
        Quantities.require(new BigDecimal("1000000"), "q");
        for (String bad : new String[] {"0", "-1", "0.0001", "1000000.001"}) {
            assertThatThrownBy(() -> Quantities.require(new BigDecimal(bad), "q")).as(bad).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> Quantities.require(null, "q")).isInstanceOf(IllegalArgumentException.class);
        assertThat(Quantities.requireNonNegative(BigDecimal.ZERO, "q")).isEqualByComparingTo("0");
        assertThatThrownBy(() -> Quantities.requireNonNegative(new BigDecimal("-0.5"), "q")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void dateRangeValidation() {
        LocalDate today = LocalDate.of(2026, 3, 15);
        DateRange def = DateRange.orDefault(null, null, today);
        assertThat(def.to()).isEqualTo(today);
        assertThat(def.from()).isEqualTo(today.minusDays(29));
        DateRange full = DateRange.orDefault(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), today);
        assertThat(full.from()).isEqualTo(LocalDate.of(2026, 1, 1));
        assertThatThrownBy(() -> DateRange.orDefault(today.plusDays(1), today, today)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DateRange.orDefault(today, null, today)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DateRange.orDefault(null, today, today)).isInstanceOf(IllegalArgumentException.class);
        // 367 days is too long, 366 is the maximum
        assertThatThrownBy(() -> new DateRange(LocalDate.of(2025, 1, 1), LocalDate.of(2026, 1, 2))).isInstanceOf(IllegalArgumentException.class);
        new DateRange(LocalDate.of(2024, 1, 1), LocalDate.of(2024, 12, 31)); // leap year, 366 days
        assertThat(DateRange.optional(null, null)).isNull();
    }

    @Test
    void dateRangeUsesRiyadhDaysAndAnExclusiveEnd() {
        DateRange r = new DateRange(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 1));
        // Riyadh is UTC+3: the day starts at 21:00 UTC the evening before and lasts 24 hours
        assertThat(r.start().atOffset(ZoneOffset.UTC).getHour()).isEqualTo(21);
        assertThat(r.endExclusive().getEpochSecond() - r.start().getEpochSecond()).isEqualTo(86_400L);
    }
}
