package com.smms.controller;

import com.smms.dto.response.CenterResponse;
import com.smms.dto.response.ControlRoomResponse;
import com.smms.dto.response.RegionResponse;
import com.smms.entity.Center;
import com.smms.entity.ControlRoom;
import com.smms.entity.Region;
import com.smms.entity.User;
import com.smms.entity.UserControlRoomAccess;
import com.smms.enums.UserRole;
import com.smms.exception.ResourceNotFoundException;
import com.smms.repository.CenterRepository;
import com.smms.repository.ControlRoomRepository;
import com.smms.repository.RegionRepository;
import com.smms.repository.TenantRepository;
import com.smms.repository.UserControlRoomAccessRepository;
import com.smms.security.AdminScopePolicy;
import com.smms.security.TenantSecurityService;
import com.smms.service.CenterService;
import com.smms.service.ControlRoomService;
import com.smms.service.PlanEnforcementService;
import com.smms.service.RegionService;
import com.smms.util.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.data.repository.query.parser.PartTree;

import java.util.List;
import java.util.UUID;

import static com.smms.support.Fixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * M3-C: what /admin/control-rooms, /admin/centers and /admin/regions list per role. Rooms and
 * centers of another region or another tenant never come back, whatever centerId/regionId says.
 */
class ControlRoomListingScopeTest {

    private final UUID tA = UUID.randomUUID();
    private final UUID tB = UUID.randomUUID();

    private ControlRoomRepository crRepo;
    private UserControlRoomAccessRepository accessRepo;
    private CenterRepository centerRepo;
    private RegionRepository regionRepo;
    private TenantSecurityService tenantSecurity;
    private AdminController controller;

    private Region regionA1, regionA2;
    private Center centerA1, centerA1b, centerA2;
    private ControlRoom roomA1, roomA1b, roomA2;

    @BeforeEach
    void setUp() {
        crRepo = mock(ControlRoomRepository.class);
        accessRepo = mock(UserControlRoomAccessRepository.class);
        centerRepo = mock(CenterRepository.class);
        regionRepo = mock(RegionRepository.class);
        tenantSecurity = mock(TenantSecurityService.class);
        AdminScopePolicy policy = new AdminScopePolicy(mock(TenantRepository.class));
        controller = new AdminController(
            new RegionService(regionRepo, tenantSecurity, policy),
            new CenterService(centerRepo, mock(PlanEnforcementService.class), tenantSecurity, policy),
            new ControlRoomService(crRepo, tenantSecurity, policy),
            accessRepo);

        regionA1 = region(tA);
        regionA2 = region(tA);
        centerA1 = center(regionA1, tA);
        centerA1b = center(regionA1, tA);
        centerA2 = center(regionA2, tA);
        roomA1 = room(centerA1, tA);
        roomA1b = room(centerA1b, tA);
        roomA2 = room(centerA2, tA);
        TenantContext.set(tA);

        when(crRepo.findAllByTenantIdAndCenterIdAndIsActiveTrue(tA, centerA1.getId())).thenReturn(List.of(roomA1));
        when(crRepo.findAllByTenantIdAndCenterIdAndIsActiveTrue(tA, centerA1b.getId())).thenReturn(List.of(roomA1b));
        when(crRepo.findAllByTenantIdAndCenterIdAndIsActiveTrue(tA, centerA2.getId())).thenReturn(List.of(roomA2));
        when(crRepo.findAllByTenantIdAndIsActiveTrue(tA)).thenReturn(List.of(roomA1, roomA1b, roomA2));
        when(crRepo.findAllByTenantIdAndCenterRegionIdAndIsActiveTrue(tA, regionA1.getId()))
            .thenReturn(List.of(roomA1, roomA1b));
        when(crRepo.findAllByTenantIdAndCenterRegionIdAndIsActiveTrue(tA, regionA2.getId()))
            .thenReturn(List.of(roomA2));
        when(tenantSecurity.requireCenter(centerA1.getId())).thenReturn(centerA1);
        when(tenantSecurity.requireCenter(centerA2.getId())).thenReturn(centerA2);
        when(centerRepo.findAllByTenantIdAndDeletedAtIsNull(tA)).thenReturn(List.of(centerA1, centerA1b, centerA2));
        when(centerRepo.findAllByTenantIdAndRegionIdAndDeletedAtIsNull(tA, regionA1.getId()))
            .thenReturn(List.of(centerA1, centerA1b));
        when(centerRepo.findAllByTenantIdAndRegionIdAndDeletedAtIsNull(tA, regionA2.getId()))
            .thenReturn(List.of(centerA2));
        when(regionRepo.findAllByTenantIdAndIsActiveTrueAndDeletedAtIsNull(tA)).thenReturn(List.of(regionA1, regionA2));
    }

    @AfterEach
    void clear() { TenantContext.clear(); }

    private List<UUID> roomIds(User actor, UUID centerId) {
        return controller.controlRooms(centerId, actor).getBody().stream().map(ControlRoomResponse::getId).toList();
    }

    // ── center-scoped roles: the request centerId is ignored ─

    @ParameterizedTest
    @EnumSource(value = UserRole.class, names = {"hospital_supervisor", "maintenance_manager", "electrical_manager", "control_room"})
    void centerScopedRolesAlwaysGetOwnCenterWhateverCenterIdSays(UserRole role) {
        User actor = user(role, centerA1, null, tA);
        assertThat(roomIds(actor, centerA2.getId())).containsExactly(roomA1.getId());   // foreign center
        assertThat(roomIds(actor, centerA1.getId())).containsExactly(roomA1.getId());   // own center
        assertThat(roomIds(actor, null)).containsExactly(roomA1.getId());               // none: NOT the whole tenant
        verify(crRepo, never()).findAllByTenantIdAndIsActiveTrue(any());
        verify(crRepo, never()).findAllByTenantIdAndCenterIdAndIsActiveTrue(tA, centerA2.getId());
    }

    @ParameterizedTest
    @EnumSource(value = UserRole.class, names = {"hospital_supervisor", "maintenance_manager", "electrical_manager", "control_room"})
    void centerScopedRoleWithoutCenterGetsNothing(UserRole role) {
        User actor = user(role, null, null, tA);
        assertThat(roomIds(actor, null)).isEmpty();
        assertThat(roomIds(actor, centerA2.getId())).isEmpty();
        verifyNoInteractions(crRepo);
    }

    // ── region supervisor ────────────────────────────────────

    @Test
    void regionSupervisorSeesOwnRegionRoomsWithoutCenterId() {
        User actor = user(UserRole.region_supervisor, null, regionA1, tA);
        assertThat(roomIds(actor, null)).containsExactlyInAnyOrder(roomA1.getId(), roomA1b.getId());
        verify(crRepo, never()).findAllByTenantIdAndIsActiveTrue(any());
    }

    @Test
    void regionSupervisorCanNarrowToACenterOfOwnRegion() {
        User actor = user(UserRole.region_supervisor, null, regionA1, tA);
        assertThat(roomIds(actor, centerA1b.getId())).containsExactly(roomA1b.getId());
    }

    @Test
    void regionSupervisorGetsNothingForACenterOfAnotherRegion() {
        User actor = user(UserRole.region_supervisor, null, regionA1, tA);
        assertThat(roomIds(actor, centerA2.getId())).isEmpty();
        assertThat(roomIds(actor, UUID.randomUUID())).isEmpty();
    }

    @Test
    void regionSupervisorWithoutRegionGetsNothing() {
        User actor = user(UserRole.region_supervisor, null, null, tA);
        assertThat(roomIds(actor, null)).isEmpty();
        verifyNoInteractions(crRepo);
    }

    @Test
    void regionSupervisorOfAnotherTenantNeverSeesTenantARooms() {
        TenantContext.set(tB);
        User actor = user(UserRole.region_supervisor, null, regionA1, tB); // region id of tenant A, tenant B token
        assertThat(roomIds(actor, null)).isEmpty();
        verify(crRepo).findAllByTenantIdAndCenterRegionIdAndIsActiveTrue(tB, regionA1.getId());
    }

    // ── system admin ─────────────────────────────────────────

    @Test
    void tenantSystemAdminListsOnlyThroughTheTenantFilter() {
        User actor = user(UserRole.system_admin, null, null, tA);
        assertThat(roomIds(actor, null)).hasSize(3);
        assertThat(roomIds(actor, centerA2.getId())).containsExactly(roomA2.getId());
        TenantContext.set(tB);
        assertThat(roomIds(user(UserRole.system_admin, null, null, tB), centerA1.getId())).isEmpty();
    }

    // ── roles that only see granted rooms ────────────────────

    @ParameterizedTest
    @EnumSource(value = UserRole.class, names = {"technician", "resident", "department", "warehouse", "operating_company_manager", "other"})
    void otherRolesSeeOnlyTheirGrantsAndCenterIdIsIgnored(UserRole role) {
        User actor = user(role, centerA1, null, tA);
        when(accessRepo.findByUserId(actor.getId())).thenReturn(List.of(
            UserControlRoomAccess.builder().user(actor).controlRoom(roomA1).build()));
        when(crRepo.findAllByIdInAndIsActiveTrue(any())).thenReturn(List.of(roomA1));
        assertThat(roomIds(actor, centerA2.getId())).containsExactly(roomA1.getId());
        verify(crRepo, never()).findAllByTenantIdAndCenterIdAndIsActiveTrue(any(), any());
        verify(crRepo, never()).findAllByTenantIdAndIsActiveTrue(any());
    }

    @Test
    void ungrantedRoleGetsAnEmptyList() {
        User actor = user(UserRole.technician, centerA1, null, tA);
        when(accessRepo.findByUserId(actor.getId())).thenReturn(List.of());
        assertThat(roomIds(actor, null)).isEmpty();
    }

    @Test
    void grantedRoomOfAnotherTenantIsFilteredOut() {
        User actor = user(UserRole.technician, centerA1, null, tA);
        ControlRoom foreign = room(center(region(tB), tB), tB);
        when(accessRepo.findByUserId(actor.getId())).thenReturn(List.of(
            UserControlRoomAccess.builder().user(actor).controlRoom(foreign).build()));
        when(crRepo.findAllByIdInAndIsActiveTrue(any())).thenReturn(List.of(foreign));
        assertThat(roomIds(actor, null)).isEmpty();
    }

    // ── centers and regions ──────────────────────────────────

    private List<UUID> centerIds(User actor, UUID regionId) {
        return controller.centers(regionId, actor).getBody().stream().map(CenterResponse::getId).toList();
    }

    private List<UUID> regionIds(User actor) {
        return controller.regions(actor).getBody().stream().map(RegionResponse::getId).toList();
    }

    @Test
    void systemAdminListsEverythingOfTheTenant() {
        User actor = user(UserRole.system_admin, null, null, tA);
        assertThat(centerIds(actor, null)).hasSize(3);
        assertThat(centerIds(actor, regionA2.getId())).containsExactly(centerA2.getId());
        assertThat(regionIds(actor)).containsExactlyInAnyOrder(regionA1.getId(), regionA2.getId());
    }

    @Test
    void regionSupervisorListsOwnRegionOnly() {
        User actor = user(UserRole.region_supervisor, null, regionA1, tA);
        assertThat(centerIds(actor, null)).containsExactlyInAnyOrder(centerA1.getId(), centerA1b.getId());
        assertThat(centerIds(actor, regionA1.getId())).containsExactlyInAnyOrder(centerA1.getId(), centerA1b.getId());
        assertThat(centerIds(actor, regionA2.getId())).isEmpty();
        assertThat(regionIds(actor)).containsExactly(regionA1.getId());
    }

    @Test
    void regionSupervisorWithoutRegionListsNothing() {
        User actor = user(UserRole.region_supervisor, null, null, tA);
        assertThat(centerIds(actor, null)).isEmpty();
        assertThat(regionIds(actor)).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(value = UserRole.class, names = {"hospital_supervisor", "maintenance_manager", "control_room", "technician", "resident", "department"})
    void everyoneElseSeesOwnCenterAndItsRegionOnly(UserRole role) {
        User actor = user(role, centerA1, null, tA);
        assertThat(centerIds(actor, null)).containsExactly(centerA1.getId());
        assertThat(centerIds(actor, regionA1.getId())).containsExactly(centerA1.getId());
        assertThat(centerIds(actor, regionA2.getId())).isEmpty();
        assertThat(regionIds(actor)).containsExactly(regionA1.getId());
    }

    @Test
    void userWithoutCenterListsNoCentersAndNoRegions() {
        User actor = user(UserRole.hospital_supervisor, null, null, tA);
        assertThat(centerIds(actor, null)).isEmpty();
        assertThat(regionIds(actor)).isEmpty();
    }

    @Test
    void ownCenterOfAnotherTenantIsNotListed() {
        User actor = user(UserRole.hospital_supervisor, centerA2, null, tB);
        TenantContext.set(tB);
        when(tenantSecurity.requireCenter(centerA2.getId())).thenThrow(new ResourceNotFoundException("Center not found"));
        assertThat(centerIds(actor, null)).isEmpty();
        assertThat(regionIds(actor)).isEmpty();
    }

    // ── repository method names resolve against the entities ─

    @Test
    void newRepositoryDerivationsResolveToEntityProperties() {
        new PartTree("findAllByTenantIdAndCenterRegionIdAndIsActiveTrue", ControlRoom.class);
        new PartTree("findAllByCenterRegionIdAndIsActiveTrue", ControlRoom.class);
        new PartTree("findByIdAndTenantId", com.smms.entity.MaintenanceType.class);
    }
}
