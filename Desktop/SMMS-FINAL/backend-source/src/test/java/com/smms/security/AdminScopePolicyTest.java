package com.smms.security;

import com.smms.entity.Center;
import com.smms.entity.ControlRoom;
import com.smms.entity.Region;
import com.smms.entity.User;
import com.smms.enums.UserRole;
import com.smms.exception.ResourceNotFoundException;
import com.smms.repository.TenantRepository;
import com.smms.security.AdminScopePolicy.Kind;
import com.smms.security.AdminScopePolicy.UserListScope;
import com.smms.util.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.security.access.AccessDeniedException;

import java.util.UUID;

import static com.smms.support.Fixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AdminScopePolicyTest {

    private final UUID tA = UUID.randomUUID();
    private final UUID tB = UUID.randomUUID();
    private TenantRepository tenantRepo;
    private AdminScopePolicy policy;

    @BeforeEach
    void setUp() {
        tenantRepo = mock(TenantRepository.class);
        policy = new AdminScopePolicy(tenantRepo);
    }

    @AfterEach
    void clear() { TenantContext.clear(); }

    // ── ranks / role assignment ─────────────────────────────

    @Test
    void ranksAreOrdered() {
        assertThat(policy.rank(UserRole.system_admin)).isGreaterThan(policy.rank(UserRole.region_supervisor));
        assertThat(policy.rank(UserRole.region_supervisor)).isGreaterThan(policy.rank(UserRole.hospital_supervisor));
        assertThat(policy.rank(UserRole.hospital_supervisor)).isGreaterThan(policy.rank(UserRole.technician));
        assertThat(policy.rank(UserRole.other)).isEqualTo(policy.rank(UserRole.resident));
    }

    @ParameterizedTest
    @EnumSource(UserRole.class)
    void platformAdminMayAssignAnyRole(UserRole target) {
        User admin = user(UserRole.system_admin, null, null, null);
        assertThatCode(() -> policy.assertCanAssignRole(admin, target)).doesNotThrowAnyException();
    }

    @Test
    void onlyPlatformLevelAdminMayMintSystemAdmin() {
        User admin = user(UserRole.system_admin, null, null, null);
        TenantContext.set(tA);
        assertThatThrownBy(() -> policy.assertCanAssignRole(admin, UserRole.system_admin))
            .isInstanceOf(AccessDeniedException.class);
        assertThatCode(() -> policy.assertCanAssignRole(admin, UserRole.technician)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @EnumSource(UserRole.class)
    void regionSupervisorOnlyBelowRegionRank(UserRole target) {
        User actor = user(UserRole.region_supervisor, null, region(tA), tA);
        boolean allowed = target != UserRole.system_admin && target != UserRole.region_supervisor;
        if (allowed) assertThatCode(() -> policy.assertCanAssignRole(actor, target)).doesNotThrowAnyException();
        else assertThatThrownBy(() -> policy.assertCanAssignRole(actor, target)).isInstanceOf(AccessDeniedException.class);
    }

    @ParameterizedTest
    @EnumSource(UserRole.class)
    void hospitalSupervisorOnlyBelowHospitalRank(UserRole target) {
        User actor = user(UserRole.hospital_supervisor, center(region(tA), tA), null, tA);
        boolean allowed = target != UserRole.system_admin && target != UserRole.region_supervisor
            && target != UserRole.hospital_supervisor;
        if (allowed) assertThatCode(() -> policy.assertCanAssignRole(actor, target)).doesNotThrowAnyException();
        else assertThatThrownBy(() -> policy.assertCanAssignRole(actor, target)).isInstanceOf(AccessDeniedException.class);
    }

    @ParameterizedTest
    @EnumSource(value = UserRole.class, names = {"control_room", "technician", "resident", "department", "other",
        "warehouse", "maintenance_manager", "electrical_manager", "operating_company_manager"})
    void otherRolesCannotAssignAnything(UserRole actorRole) {
        User actor = user(actorRole, center(region(tA), tA), null, tA);
        assertThatThrownBy(() -> policy.assertCanAssignRole(actor, UserRole.resident))
            .isInstanceOf(AccessDeniedException.class);
    }

    // ── center / region / control room scope ────────────────

    @Test
    void centerScopeByRole() {
        Region r1 = region(tA), r2 = region(tA);
        Center c1 = center(r1, tA), c1b = center(r1, tA), c2 = center(r2, tA);
        User admin = user(UserRole.system_admin, null, null, null);
        User reg = user(UserRole.region_supervisor, null, r1, tA);
        User hosp = user(UserRole.hospital_supervisor, c1, null, tA);

        assertThat(policy.centerInScope(admin, c2)).isTrue();
        assertThat(policy.centerInScope(reg, c1)).isTrue();
        assertThat(policy.centerInScope(reg, c1b)).isTrue();
        assertThat(policy.centerInScope(reg, c2)).isFalse();
        assertThat(policy.centerInScope(hosp, c1)).isTrue();
        assertThat(policy.centerInScope(hosp, c1b)).isFalse();
        assertThat(policy.centerInScope(user(UserRole.technician, c1, null, tA), c1)).isFalse();
        assertThat(policy.centerInScope(null, c1)).isFalse();
        assertThatThrownBy(() -> policy.assertCenterInScope(hosp, c2)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void supervisorsWithoutScopeAreDeniedEverything() {
        Center c = center(region(tA), tA);
        assertThat(policy.centerInScope(user(UserRole.region_supervisor, null, null, tA), c)).isFalse();
        assertThat(policy.centerInScope(user(UserRole.hospital_supervisor, null, null, tA), c)).isFalse();
    }

    @Test
    void regionScopeByRole() {
        Region r1 = region(tA), r2 = region(tA);
        User admin = user(UserRole.system_admin, null, null, null);
        User reg = user(UserRole.region_supervisor, null, r1, tA);
        User hosp = user(UserRole.hospital_supervisor, center(r1, tA), null, tA);
        assertThat(policy.regionInScope(admin, r2)).isTrue();
        assertThat(policy.regionInScope(reg, r1)).isTrue();
        assertThat(policy.regionInScope(reg, r2)).isFalse();
        assertThat(policy.regionInScope(hosp, r1)).isFalse();
        assertThatThrownBy(() -> policy.assertRegionInScope(reg, r2)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void controlRoomScopeFollowsItsCenter() {
        Region r = region(tA);
        Center mine = center(r, tA), other = center(r, tA);
        User hosp = user(UserRole.hospital_supervisor, mine, null, tA);
        ControlRoom ok = room(mine, tA), no = room(other, tA);
        assertThatCode(() -> policy.assertControlRoomInScope(hosp, ok)).doesNotThrowAnyException();
        assertThatThrownBy(() -> policy.assertControlRoomInScope(hosp, no)).isInstanceOf(ResourceNotFoundException.class);
    }

    // ── managing users ──────────────────────────────────────

    @Test
    void manageUserScopeThenRank() {
        Region r1 = region(tA), r2 = region(tA);
        Center c1 = center(r1, tA), c2 = center(r2, tA);
        User hosp = user(UserRole.hospital_supervisor, c1, null, tA);
        User reg = user(UserRole.region_supervisor, null, r1, tA);

        assertThatCode(() -> policy.assertCanManageUser(hosp, user(UserRole.technician, c1, null, tA))).doesNotThrowAnyException();
        assertThatThrownBy(() -> policy.assertCanManageUser(hosp, user(UserRole.technician, c2, null, tA)))
            .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> policy.assertCanManageUser(hosp, user(UserRole.hospital_supervisor, c1, null, tA)))
            .isInstanceOf(AccessDeniedException.class);

        assertThatCode(() -> policy.assertCanManageUser(reg, user(UserRole.hospital_supervisor, c1, null, tA))).doesNotThrowAnyException();
        assertThatCode(() -> policy.assertCanManageUser(reg, user(UserRole.other, null, r1, tA))).doesNotThrowAnyException();
        assertThatThrownBy(() -> policy.assertCanManageUser(reg, user(UserRole.technician, c2, null, tA)))
            .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> policy.assertCanManageUser(reg, user(UserRole.region_supervisor, null, r1, tA)))
            .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> policy.assertCanManageUser(reg, user(UserRole.system_admin, null, null, null)))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @ParameterizedTest
    @EnumSource(value = UserRole.class, names = {"control_room", "technician", "resident", "other", "warehouse"})
    void nonManagersCannotManageAnyone(UserRole role) {
        Center c = center(region(tA), tA);
        assertThatThrownBy(() -> policy.assertCanManageUser(user(role, c, null, tA), user(UserRole.resident, c, null, tA)))
            .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> policy.assertCanManageUser(null, user(UserRole.resident, c, null, tA)))
            .isInstanceOf(AccessDeniedException.class);
    }

    // ── list scope ──────────────────────────────────────────

    @Test
    void listScopeByRole() {
        Region r = region(tA);
        Center c = center(r, tA);
        assertThat(policy.resolveUserListScope(user(UserRole.system_admin, null, null, null)).kind()).isEqualTo(Kind.ALL);

        UserListScope reg = policy.resolveUserListScope(user(UserRole.region_supervisor, null, r, tA));
        assertThat(reg.kind()).isEqualTo(Kind.REGION);
        assertThat(reg.regionId()).isEqualTo(r.getId());
        assertThat(reg.excludedRoles()).contains(UserRole.system_admin, UserRole.region_supervisor);

        UserListScope hosp = policy.resolveUserListScope(user(UserRole.hospital_supervisor, c, null, tA));
        assertThat(hosp.kind()).isEqualTo(Kind.CENTER);
        assertThat(hosp.centerId()).isEqualTo(c.getId());
        assertThat(hosp.excludedRoles()).contains(UserRole.system_admin, UserRole.region_supervisor);

        assertThat(policy.resolveUserListScope(user(UserRole.region_supervisor, null, null, tA)).kind()).isEqualTo(Kind.NONE);
        assertThat(policy.resolveUserListScope(user(UserRole.hospital_supervisor, null, null, tA)).kind()).isEqualTo(Kind.NONE);
        assertThat(policy.resolveUserListScope(null).kind()).isEqualTo(Kind.NONE);
    }

    @ParameterizedTest
    @EnumSource(value = UserRole.class, names = {"control_room", "technician", "resident", "department", "other",
        "warehouse", "maintenance_manager", "electrical_manager", "operating_company_manager"})
    void everyOtherRoleHasNoListScope(UserRole role) {
        Center c = center(region(tA), tA);
        assertThat(policy.resolveUserListScope(user(role, c, null, tA)).kind()).isEqualTo(Kind.NONE);
    }

    // ── tenant resolution (SEC-A) ───────────────────────────

    @Test
    void contextTenantWinsAndRequestedTenantIsIgnored() {
        TenantContext.set(tA);
        Center c = center(region(tA), tA);
        assertThat(policy.resolveTenantForNewEntity(tB, c)).isEqualTo(tA);
        assertThat(policy.resolveTenantForNewEntity(null)).isEqualTo(tA);
    }

    @Test
    void contextTenantRejectsForeignAnchor() {
        TenantContext.set(tA);
        Center foreign = center(region(tB), tB);
        assertThatThrownBy(() -> policy.resolveTenantForNewEntity(null, foreign)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void platformAdminDerivesTenantFromAnchors() {
        Region r = region(tB);
        Center c = center(r, tB);
        assertThat(policy.resolveTenantForNewEntity(null, c, r, null)).isEqualTo(tB);
        assertThat(policy.resolveTenantForNewEntity(tB, c)).isEqualTo(tB);
    }

    @Test
    void platformAdminMismatchedAnchorsOrRequestedTenantAre400() {
        Center ca = center(region(tA), tA);
        Center cb = center(region(tB), tB);
        assertThatThrownBy(() -> policy.resolveTenantForNewEntity(null, ca, cb)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> policy.resolveTenantForNewEntity(tB, ca)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void platformAdminAnchorWithoutTenantIs400() {
        Center orphan = center(region(null), null);
        assertThatThrownBy(() -> policy.resolveTenantForNewEntity(null, orphan)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void platformAdminNoAnchorNeedsExistingTenant() {
        assertThatThrownBy(() -> policy.resolveTenantForNewEntity(null)).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("tenantId is required");
        assertThatThrownBy(() -> policy.resolveTenantForNewEntity(tA)).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Unknown");
        when(tenantRepo.existsById(tA)).thenReturn(true);
        assertThat(policy.resolveTenantForNewEntity(tA)).isEqualTo(tA);
    }

    @Test
    void assertSameTenantAcceptsNullPartsAndRejectsMixes() {
        Center a = center(region(tA), tA), b = center(region(tB), tB);
        assertThatCode(() -> policy.assertSameTenant(a, null, a)).doesNotThrowAnyException();
        assertThatThrownBy(() -> policy.assertSameTenant(a, b)).isInstanceOf(IllegalArgumentException.class);
    }
}
