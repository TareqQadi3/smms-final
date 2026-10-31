package com.smms.reports;

import com.smms.entity.Center;
import com.smms.entity.Region;
import com.smms.entity.User;
import com.smms.enums.UserRole;
import com.smms.exception.ResourceNotFoundException;
import com.smms.security.WorkOrderScope;
import com.smms.security.WorkOrderScope.Location;
import com.smms.support.Fixtures;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.security.access.AccessDeniedException;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReportScopeTest {

    private final UUID tenantA = UUID.randomUUID(), tenantB = UUID.randomUUID();
    private final Region regionA = Fixtures.region(tenantA);
    private final Center centerA1 = Fixtures.center(regionA, tenantA);
    private final Center centerA2 = Fixtures.center(regionA, tenantA);
    private final Region regionB = Fixtures.region(tenantB);
    private final Center centerB = Fixtures.center(regionB, tenantB);
    private final Map<UUID, ReportScope.CenterInfo> db = Map.of(
        centerA1.getId(), new ReportScope.CenterInfo(tenantA, regionA.getId()),
        centerA2.getId(), new ReportScope.CenterInfo(tenantA, regionA.getId()),
        centerB.getId(), new ReportScope.CenterInfo(tenantB, regionB.getId()));
    private final Function<UUID, Optional<ReportScope.CenterInfo>> lookup = id -> Optional.ofNullable(db.get(id));

    private WorkOrderScope centerGrant(User u) { return WorkOrderScope.of(u.getId(), null, new Location(centerA1.getId(), null, null, false)); }

    @Test
    void centerManagerSeesOnlyOwnCenterAndTenantNotOwnSubmissions() {
        User hs = Fixtures.user(UserRole.hospital_supervisor, centerA1, regionA, tenantA);
        ReportScope s = ReportScope.resolve(hs, centerGrant(hs), tenantA, null, null, lookup);
        assertThat(s).isEqualTo(new ReportScope(tenantA, centerA1.getId(), null));
        String sql = s.sql("w", "c", new MapSqlParameterSource());
        assertThat(sql).contains("w.tenant_id = :sc_tenant").contains("w.center_id = :sc_center").contains("deleted_at IS NULL");
        assertThat(sql).doesNotContain("requested_by");
    }

    @Test
    void managerCannotNarrowToAnotherCenterOrTenant() {
        User hs = Fixtures.user(UserRole.hospital_supervisor, centerA1, regionA, tenantA);
        assertThatThrownBy(() -> ReportScope.resolve(hs, centerGrant(hs), tenantA, centerA2.getId(), null, lookup))
            .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> ReportScope.resolve(hs, centerGrant(hs), tenantA, centerB.getId(), null, lookup))
            .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> ReportScope.resolve(hs, centerGrant(hs), tenantA, UUID.randomUUID(), null, lookup))
            .isInstanceOf(ResourceNotFoundException.class);
        assertThat(ReportScope.resolve(hs, centerGrant(hs), tenantA, centerA1.getId(), null, lookup).centerId()).isEqualTo(centerA1.getId());
    }

    @Test
    void managerMayNotUseRegionFilter() {
        User hs = Fixtures.user(UserRole.hospital_supervisor, centerA1, regionA, tenantA);
        assertThatThrownBy(() -> ReportScope.resolve(hs, centerGrant(hs), tenantA, null, regionA.getId(), lookup))
            .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void regionSupervisorSeesRegionCentersOnly() {
        User rs = Fixtures.user(UserRole.region_supervisor, null, regionA, tenantA);
        WorkOrderScope ws = WorkOrderScope.of(null, null, new Location(null, regionA.getId(), null, false));
        assertThat(ReportScope.resolve(rs, ws, tenantA, null, null, lookup)).isEqualTo(new ReportScope(tenantA, null, regionA.getId()));
        assertThat(ReportScope.resolve(rs, ws, tenantA, centerA2.getId(), null, lookup).centerId()).isEqualTo(centerA2.getId());
        assertThat(ReportScope.resolve(rs, ws, tenantA, null, regionA.getId(), lookup).regionId()).isEqualTo(regionA.getId());
        assertThatThrownBy(() -> ReportScope.resolve(rs, ws, tenantA, centerB.getId(), null, lookup)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> ReportScope.resolve(rs, ws, tenantA, null, regionB.getId(), lookup)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void tenantAdminIsBoundToTenantPlatformAdminIsNot() {
        User sa = Fixtures.user(UserRole.system_admin, null, null, tenantA);
        assertThat(ReportScope.resolve(sa, WorkOrderScope.all(), tenantA, null, null, lookup).tenantId()).isEqualTo(tenantA);
        assertThatThrownBy(() -> ReportScope.resolve(sa, WorkOrderScope.all(), tenantA, centerB.getId(), null, lookup))
            .isInstanceOf(ResourceNotFoundException.class);
        User platform = Fixtures.user(UserRole.system_admin, null, null, null);
        ReportScope p = ReportScope.resolve(platform, WorkOrderScope.all(), null, null, null, lookup);
        assertThat(p.tenantId()).isNull();
        assertThat(p.sql("w", "c", new MapSqlParameterSource())).doesNotContain("tenant");
    }

    @Test
    void rolesWithoutLocationGrantAreDenied() {
        User te = Fixtures.user(UserRole.technician, centerA1, regionA, tenantA);
        User re = Fixtures.user(UserRole.resident, centerA1, regionA, tenantA);
        User wh = Fixtures.user(UserRole.warehouse, centerA1, regionA, tenantA);
        User cr = Fixtures.user(UserRole.control_room, centerA1, regionA, tenantA);
        Function<User, WorkOrderScope> none = u -> WorkOrderScope.none();
        assertThatThrownBy(() -> ReportScope.resolve(te, WorkOrderScope.of(null, te.getId(), null), tenantA, null, null, lookup))
            .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> ReportScope.resolve(re, WorkOrderScope.of(re.getId(), null, null), tenantA, null, null, lookup))
            .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> ReportScope.resolve(wh, WorkOrderScope.of(null, null, new Location(centerA1.getId(), null, null, true)), tenantA, null, null, lookup))
            .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> ReportScope.resolve(cr, WorkOrderScope.of(cr.getId(), null, new Location(centerA1.getId(), null, Set.of(UUID.randomUUID()), false)), tenantA, null, null, lookup))
            .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> ReportScope.resolve(te, none.apply(te), tenantA, null, null, lookup)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> ReportScope.resolve(null, WorkOrderScope.none(), tenantA, null, null, lookup)).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void tenantlessNonAdminIsDenied() {
        User hs = Fixtures.user(UserRole.hospital_supervisor, centerA1, regionA, null);
        assertThatThrownBy(() -> ReportScope.resolve(hs, centerGrant(hs), null, null, null, lookup)).isInstanceOf(AccessDeniedException.class);
    }
}
