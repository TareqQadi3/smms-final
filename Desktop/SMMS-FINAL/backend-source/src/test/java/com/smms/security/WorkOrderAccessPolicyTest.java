package com.smms.security;

import com.smms.entity.Center;
import com.smms.entity.ControlRoom;
import com.smms.entity.Region;
import com.smms.entity.User;
import com.smms.entity.WorkOrder;
import com.smms.enums.UserRole;
import com.smms.exception.ResourceNotFoundException;
import com.smms.repository.UserControlRoomAccessRepository;
import com.smms.repository.WorkOrderRepository;
import com.smms.security.WorkOrderScope.Location;
import com.smms.util.TenantContext;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Root;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.AccessDeniedException;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class WorkOrderAccessPolicyTest {

    private WorkOrderRepository workOrderRepo;
    private UserControlRoomAccessRepository accessRepo;
    private WorkOrderAccessPolicy policy;

    private final UUID centerA = UUID.randomUUID();
    private final UUID centerB = UUID.randomUUID();
    private final UUID regionA = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        workOrderRepo = mock(WorkOrderRepository.class);
        accessRepo = mock(UserControlRoomAccessRepository.class);
        policy = new WorkOrderAccessPolicy(workOrderRepo, accessRepo);
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    private static User user(UserRole role, UUID center, UUID region) {
        User u = new User();
        u.setId(UUID.randomUUID());
        u.setRole(role);
        if (center != null) {
            Center c = new Center();
            c.setId(center);
            u.setCenter(c);
        }
        if (region != null) {
            Region r = new Region();
            r.setId(region);
            u.setRegion(r);
        }
        return u;
    }

    private static ControlRoom room(UUID center) {
        ControlRoom cr = new ControlRoom();
        cr.setId(UUID.randomUUID());
        Center c = new Center();
        c.setId(center);
        cr.setCenter(c);
        return cr;
    }

    // ── resolveScope ─────────────────────────────────────

    @Test
    void systemAdminUnrestricted() {
        WorkOrderScope s = policy.resolveScope(user(UserRole.system_admin, null, null));
        assertThat(s.unrestricted()).isTrue();
        verifyNoInteractions(accessRepo);
    }

    @Test
    void technicianAssignedOnly() {
        User t = user(UserRole.technician, centerA, regionA);
        WorkOrderScope s = policy.resolveScope(t);
        assertThat(s.assignedToId()).isEqualTo(t.getId());
        assertThat(s.requestedById()).isNull();
        assertThat(s.location()).isNull();
        assertThat(s.unrestricted()).isFalse();
    }

    @ParameterizedTest
    @EnumSource(value = UserRole.class, names = {"resident", "department"})
    void ownOnly(UserRole role) {
        User u = user(role, centerA, regionA);
        when(accessRepo.findControlRoomIdsByUserId(u.getId())).thenReturn(List.of(UUID.randomUUID()));
        WorkOrderScope s = policy.resolveScope(u);
        assertThat(s.requestedById()).isEqualTo(u.getId());
        assertThat(s.assignedToId()).isNull();
        assertThat(s.location()).isNull();
        verifyNoInteractions(accessRepo);
    }

    @Test
    void controlRoomCenterAndAssignedRooms() {
        User u = user(UserRole.control_room, centerA, regionA);
        UUID r1 = UUID.randomUUID();
        UUID r2 = UUID.randomUUID();
        when(accessRepo.findControlRoomIdsByUserId(u.getId())).thenReturn(List.of(r1, r2));
        WorkOrderScope s = policy.resolveScope(u);
        assertThat(s.requestedById()).isEqualTo(u.getId());
        assertThat(s.location().centerId()).isEqualTo(centerA);
        assertThat(s.location().regionId()).isNull();
        assertThat(s.location().controlRoomIds()).containsExactlyInAnyOrder(r1, r2);
        assertThat(s.location().materialsOnly()).isFalse();
    }

    @Test
    void controlRoomWithoutAccessRowsGetsOwnOnly() {
        User u = user(UserRole.control_room, centerA, null);
        when(accessRepo.findControlRoomIdsByUserId(u.getId())).thenReturn(List.of());
        WorkOrderScope s = policy.resolveScope(u);
        assertThat(s.location()).isNull();
        assertThat(s.requestedById()).isEqualTo(u.getId());
    }

    @Test
    void controlRoomWithoutCenterGetsOwnOnlyAndNoQuery() {
        User u = user(UserRole.control_room, null, null);
        WorkOrderScope s = policy.resolveScope(u);
        assertThat(s.location()).isNull();
        verify(accessRepo, never()).findControlRoomIdsByUserId(any());
    }

    @ParameterizedTest
    @EnumSource(value = UserRole.class, names = {"hospital_supervisor", "maintenance_manager",
        "electrical_manager", "operating_company_manager"})
    void centerScoped(UserRole role) {
        User u = user(role, centerA, regionA);
        WorkOrderScope s = policy.resolveScope(u);
        assertThat(s.requestedById()).isEqualTo(u.getId());
        assertThat(s.location().centerId()).isEqualTo(centerA);
        assertThat(s.location().regionId()).isNull();
        assertThat(s.location().controlRoomIds()).isNull();
        assertThat(s.location().materialsOnly()).isFalse();

        // Missing center never widens access
        WorkOrderScope noCenter = policy.resolveScope(user(role, null, regionA));
        assertThat(noCenter.location()).isNull();
    }

    @Test
    void warehouseCenterAndMaterialsOnly() {
        WorkOrderScope s = policy.resolveScope(user(UserRole.warehouse, centerA, null));
        assertThat(s.location().centerId()).isEqualTo(centerA);
        assertThat(s.location().materialsOnly()).isTrue();
        assertThat(s.requestedById()).isNull();
        assertThat(policy.resolveScope(user(UserRole.warehouse, null, regionA)).isNone()).isTrue();
    }

    @Test
    void regionSupervisorRegion() {
        WorkOrderScope s = policy.resolveScope(user(UserRole.region_supervisor, centerA, regionA));
        assertThat(s.location().regionId()).isEqualTo(regionA);
        assertThat(s.location().centerId()).isNull();
        // No fallback to the center's region
        assertThat(policy.resolveScope(user(UserRole.region_supervisor, centerA, null)).isNone()).isTrue();
    }

    @Test
    void otherDeniedEvenWithAccessRowsAndCenter() {
        User u = user(UserRole.other, centerA, regionA);
        when(accessRepo.findControlRoomIdsByUserId(u.getId())).thenReturn(List.of(UUID.randomUUID()));
        assertThat(policy.resolveScope(u).isNone()).isTrue();
        verifyNoInteractions(accessRepo);
    }

    @Test
    void nullActorOrNullRoleDenied() {
        assertThat(policy.resolveScope(null).isNone()).isTrue();
        assertThat(policy.resolveScope(user(null, centerA, regionA)).isNone()).isTrue();
        User noId = user(UserRole.system_admin, null, null);
        noId.setId(null);
        assertThat(policy.resolveScope(noId).isNone()).isTrue();
    }

    @Test
    void everyRoleIsCovered() {
        // Guards against a new role silently getting access: only these roles may ever be non-none
        Set<UserRole> deniedWithoutData = EnumSet.of(UserRole.other);
        for (UserRole role : UserRole.values()) {
            WorkOrderScope s = policy.resolveScope(user(role, null, null));
            if (deniedWithoutData.contains(role) || role == UserRole.warehouse || role == UserRole.region_supervisor) {
                assertThat(s.isNone()).as(role.name()).isTrue();
            }
        }
    }

    @Test
    void locationRejectsAllNullComponents() {
        assertThatThrownBy(() -> new Location(null, null, null, false))
            .isInstanceOf(IllegalArgumentException.class);
    }

    // ── requireReadable ──────────────────────────────────

    @Test
    void requireReadableNoneScopeIs404WithoutQuery() {
        UUID id = UUID.randomUUID();
        assertThatThrownBy(() -> policy.requireReadable(id, user(UserRole.other, centerA, regionA)))
            .isInstanceOf(ResourceNotFoundException.class);
        verifyNoInteractions(workOrderRepo);
    }

    @Test
    @SuppressWarnings("unchecked")
    void requireReadableNotMatchingIs404() {
        UUID id = UUID.randomUUID();
        when(workOrderRepo.findOne(any(Specification.class))).thenReturn(Optional.empty());
        assertThatThrownBy(() -> policy.requireReadable(id, user(UserRole.resident, centerA, null)))
            .isInstanceOf(ResourceNotFoundException.class)
            .hasMessage("WorkOrder not found: " + id);
    }

    @Test
    @SuppressWarnings("unchecked")
    void requireReadableReturnsEntity() {
        WorkOrder wo = new WorkOrder();
        when(workOrderRepo.findOne(any(Specification.class))).thenReturn(Optional.of(wo));
        assertThat(policy.requireReadable(UUID.randomUUID(), user(UserRole.resident, centerA, null))).isSameAs(wo);
    }

    @Test
    void requireReadableNullActorAndNullIdDenied() {
        assertThatThrownBy(() -> policy.requireReadable(UUID.randomUUID(), null))
            .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> policy.requireReadable(null, user(UserRole.system_admin, null, null)))
            .isInstanceOf(ResourceNotFoundException.class);
        verifyNoInteractions(workOrderRepo);
    }

    // ── Specification shape ──────────────────────────────

    @SuppressWarnings("unchecked")
    private Root<WorkOrder> root() {
        return mock(Root.class, RETURNS_DEEP_STUBS);
    }

    @SuppressWarnings("unchecked")
    private void apply(WorkOrderScope scope, Root<WorkOrder> root, CriteriaBuilder cb) {
        policy.specFor(scope).toPredicate(root, mock(CriteriaQuery.class), cb);
    }

    @Test
    void specNoneScopeIsAlwaysFalse() {
        CriteriaBuilder cb = mock(CriteriaBuilder.class, RETURNS_DEEP_STUBS);
        apply(WorkOrderScope.none(), root(), cb);
        verify(cb).disjunction();
    }

    @Test
    void specUnrestrictedHasNoScopeBranches() {
        CriteriaBuilder cb = mock(CriteriaBuilder.class, RETURNS_DEEP_STUBS);
        Root<WorkOrder> root = root();
        apply(WorkOrderScope.all(), root, cb);
        verify(cb, never()).disjunction();
        verify(cb, never()).or(any(jakarta.persistence.criteria.Predicate[].class));
    }

    @Test
    void specResidentFiltersByRequester() {
        CriteriaBuilder cb = mock(CriteriaBuilder.class, RETURNS_DEEP_STUBS);
        Root<WorkOrder> root = root();
        UUID self = UUID.randomUUID();
        apply(WorkOrderScope.of(self, null, null), root, cb);
        verify(cb).equal(root.get("requestedBy").get("id"), self);
        var assignedId = root.get("assignedTo").get("id");
        verify(cb, never()).equal(eq(assignedId), any(Object.class));
    }

    @Test
    void specControlRoomUsesCenterAndRooms() {
        CriteriaBuilder cb = mock(CriteriaBuilder.class, RETURNS_DEEP_STUBS);
        Root<WorkOrder> root = root();
        UUID r1 = UUID.randomUUID();
        UUID r2 = UUID.randomUUID();
        apply(WorkOrderScope.of(null, null, new Location(centerA, null, Set.of(r1, r2), false)), root, cb);
        verify(root.get("controlRoom").get("id")).in(Set.of(r1, r2));
        verify(cb).equal(root.get("center").get("id"), centerA);
    }

    @Test
    void specEmptyRoomSetMatchesNothing() {
        CriteriaBuilder cb = mock(CriteriaBuilder.class, RETURNS_DEEP_STUBS);
        Root<WorkOrder> root = root();
        apply(WorkOrderScope.of(UUID.randomUUID(), null, new Location(centerA, null, Set.of(), false)), root, cb);
        verify(cb).disjunction();
        verify(root.get("controlRoom").get("id"), never()).in(any(java.util.Collection.class));
    }

    @Test
    void specRegionFiltersByCenterRegion() {
        CriteriaBuilder cb = mock(CriteriaBuilder.class, RETURNS_DEEP_STUBS);
        Root<WorkOrder> root = root();
        apply(WorkOrderScope.of(null, null, new Location(null, regionA, null, false)), root, cb);
        verify(cb).equal(root.get("center").get("region").get("id"), regionA);
    }

    @Test
    void specWarehouseRequiresMaterials() {
        CriteriaBuilder cb = mock(CriteriaBuilder.class, RETURNS_DEEP_STUBS);
        Root<WorkOrder> root = root();
        apply(WorkOrderScope.of(null, null, new Location(centerA, null, null, true)), root, cb);
        verify(cb).isTrue(root.get("needsMaterials"));
    }

    @Test
    void specTenantPredicateOnlyWhenContextSet() {
        UUID tenant = UUID.randomUUID();
        TenantContext.set(tenant);
        CriteriaBuilder withTenant = mock(CriteriaBuilder.class, RETURNS_DEEP_STUBS);
        Root<WorkOrder> r1 = root();
        apply(WorkOrderScope.all(), r1, withTenant);
        verify(withTenant).equal(r1.get("tenantId"), tenant);

        TenantContext.clear();
        CriteriaBuilder noTenant = mock(CriteriaBuilder.class, RETURNS_DEEP_STUBS);
        Root<WorkOrder> r2 = root();
        apply(WorkOrderScope.all(), r2, noTenant);
        var tenantPath = r2.get("tenantId");
        verify(noTenant, never()).equal(eq(tenantPath), any(Object.class));
    }

    // ── assertCanCreateIn ────────────────────────────────

    @Test
    void createSystemAdminAllowed() {
        policy.assertCanCreateIn(room(centerA), user(UserRole.system_admin, null, null));
    }

    @ParameterizedTest
    @EnumSource(value = UserRole.class, names = {"hospital_supervisor", "maintenance_manager"})
    void createSupervisorOnlyInOwnCenter(UserRole role) {
        policy.assertCanCreateIn(room(centerA), user(role, centerA, null));
        assertThatThrownBy(() -> policy.assertCanCreateIn(room(centerB), user(role, centerA, null)))
            .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> policy.assertCanCreateIn(room(centerA), user(role, null, null)))
            .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void createControlRoomNeedsRowAndSameCenter() {
        User u = user(UserRole.control_room, centerA, null);
        ControlRoom same = room(centerA);
        ControlRoom other = room(centerB);
        when(accessRepo.existsByUserIdAndControlRoomId(u.getId(), same.getId())).thenReturn(true);
        when(accessRepo.existsByUserIdAndControlRoomId(u.getId(), other.getId())).thenReturn(true);

        policy.assertCanCreateIn(same, u);
        // Cross-center grant must not allow creating there
        assertThatThrownBy(() -> policy.assertCanCreateIn(other, u)).isInstanceOf(AccessDeniedException.class);
        // No access row
        assertThatThrownBy(() -> policy.assertCanCreateIn(room(centerA), u)).isInstanceOf(AccessDeniedException.class);
    }

    @ParameterizedTest
    @EnumSource(value = UserRole.class, names = {"resident", "department"})
    void createEndUserNeedsRow(UserRole role) {
        User u = user(role, centerA, null);
        ControlRoom cr = room(centerA);
        when(accessRepo.existsByUserIdAndControlRoomId(u.getId(), cr.getId())).thenReturn(true);
        policy.assertCanCreateIn(cr, u);
        assertThatThrownBy(() -> policy.assertCanCreateIn(room(centerA), u)).isInstanceOf(AccessDeniedException.class);
    }

    @ParameterizedTest
    @EnumSource(value = UserRole.class, names = {"technician", "other", "warehouse", "region_supervisor",
        "electrical_manager", "operating_company_manager"})
    void createDeniedForOtherRoles(UserRole role) {
        User u = user(role, centerA, regionA);
        ControlRoom cr = room(centerA);
        lenient().when(accessRepo.existsByUserIdAndControlRoomId(any(), any())).thenReturn(true);
        assertThatThrownBy(() -> policy.assertCanCreateIn(cr, u)).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void createDeniedForNullActor() {
        assertThatThrownBy(() -> policy.assertCanCreateIn(room(centerA), null))
            .isInstanceOf(AccessDeniedException.class);
    }
}
