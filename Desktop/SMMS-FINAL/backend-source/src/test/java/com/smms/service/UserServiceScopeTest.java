package com.smms.service;

import com.smms.dto.request.CreateUserRequest;
import com.smms.dto.request.UpdateUserRequest;
import com.smms.entity.*;
import com.smms.enums.UserRole;
import com.smms.exception.ResourceNotFoundException;
import com.smms.mapper.UserMapper;
import com.smms.repository.*;
import com.smms.security.AdminScopePolicy;
import com.smms.security.TenantSecurityService;
import com.smms.util.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.UUID;

import static com.smms.support.Fixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Create / update / toggle / grant rules of UserService (SEC-A, SEC-C, SEC-D). */
class UserServiceScopeTest {

    private final UUID tenantA = UUID.randomUUID();
    private final UUID tenantB = UUID.randomUUID();

    private TenantSecurityService tenantSecurity;
    private UserRepository userRepo;
    private UserControlRoomAccessRepository accessRepo;
    private PlanEnforcementService planEnforcement;
    private TenantRepository tenantRepo;
    private UserService service;

    @BeforeEach
    void setUp() {
        userRepo = mock(UserRepository.class);
        accessRepo = mock(UserControlRoomAccessRepository.class);
        tenantSecurity = mock(TenantSecurityService.class);
        planEnforcement = mock(PlanEnforcementService.class);
        tenantRepo = mock(TenantRepository.class);
        service = new UserService(userRepo, accessRepo, mock(PasswordEncoder.class),
            mock(UserMapper.class), planEnforcement, tenantSecurity, new AdminScopePolicy(tenantRepo),
            new UserAccountValidator());
        when(userRepo.save(any(User.class))).thenAnswer(i -> i.getArgument(0));
    }

    @AfterEach
    void clearTenant() { TenantContext.clear(); }

    private CreateUserRequest create(UserRole role, UUID centerId) {
        CreateUserRequest r = new CreateUserRequest();
        r.setUsername("newuser");
        r.setPassword("Passw0rd!");
        r.setFullName("New User");
        r.setRole(role);
        r.setCenterId(centerId);
        return r;
    }

    private User saved() {
        ArgumentCaptor<User> c = ArgumentCaptor.forClass(User.class);
        verify(userRepo).save(c.capture());
        return c.getValue();
    }

    private User plainUser(UserRole role) {
        return user(role, null, null, tenantA);
    }

    // ── existing behaviour kept ─────────────────────────────

    @Test
    void updateMeIgnoresRoleCenterAndRegion() {
        User me = plainUser(UserRole.technician);
        when(tenantSecurity.requireUser(me.getId())).thenReturn(me);
        UpdateUserRequest req = new UpdateUserRequest();
        req.setRole(UserRole.system_admin);
        req.setCenterId(UUID.randomUUID());
        req.setRegionId(UUID.randomUUID());
        req.setFullName("New Name");

        service.updateMe(me.getId(), req);

        assertThat(me.getRole()).isEqualTo(UserRole.technician);
        assertThat(me.getFullName()).isEqualTo("New Name");
        verify(tenantSecurity, never()).requireCenter(any());
        verify(tenantSecurity, never()).requireRegion(any());
    }

    @Test
    void hospitalSupervisorCannotGrantSystemAdmin() {
        Center own = center(region(tenantA), tenantA);
        User actor = user(UserRole.hospital_supervisor, own, null, tenantA);
        User target = user(UserRole.technician, own, null, tenantA);
        when(tenantSecurity.requireUser(target.getId())).thenReturn(target);
        UpdateUserRequest req = new UpdateUserRequest();
        req.setRole(UserRole.system_admin);

        assertThatThrownBy(() -> service.update(target.getId(), req, actor))
            .isInstanceOf(AccessDeniedException.class);
        assertThat(target.getRole()).isEqualTo(UserRole.technician);
    }

    @Test
    void regionSupervisorCannotSelfPromoteViaAdminEndpoint() {
        Region r = region(tenantA);
        User actor = user(UserRole.region_supervisor, null, r, tenantA);
        when(tenantSecurity.requireUser(actor.getId())).thenReturn(actor);
        UpdateUserRequest req = new UpdateUserRequest();
        req.setRole(UserRole.system_admin);

        assertThatThrownBy(() -> service.update(actor.getId(), req, actor))
            .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void systemAdminCannotChangeOwnRole() {
        User actor = user(UserRole.system_admin, null, null, null);
        when(tenantSecurity.requireUser(actor.getId())).thenReturn(actor);
        UpdateUserRequest req = new UpdateUserRequest();
        req.setRole(UserRole.technician);

        assertThatThrownBy(() -> service.update(actor.getId(), req, actor))
            .isInstanceOf(AccessDeniedException.class);
        assertThat(actor.getRole()).isEqualTo(UserRole.system_admin);
    }

    @Test
    void systemAdminCanAssignRoleToOthers() {
        User actor = user(UserRole.system_admin, null, null, null);
        Center c = center(region(tenantA), tenantA);
        User target = user(UserRole.technician, c, null, tenantA);
        when(tenantSecurity.requireUser(target.getId())).thenReturn(target);
        UpdateUserRequest req = new UpdateUserRequest();
        req.setRole(UserRole.hospital_supervisor);

        service.update(target.getId(), req, actor);
        assertThat(target.getRole()).isEqualTo(UserRole.hospital_supervisor);
    }

    @Test
    void hospitalSupervisorCannotMoveUserToForeignCenter() {
        Region r = region(tenantA);
        Center own = center(r, tenantA);
        Center foreign = center(r, tenantA);
        User actor = user(UserRole.hospital_supervisor, own, null, tenantA);
        User target = user(UserRole.technician, own, null, tenantA);
        when(tenantSecurity.requireUser(target.getId())).thenReturn(target);
        when(tenantSecurity.requireCenter(foreign.getId())).thenReturn(foreign);
        UpdateUserRequest req = new UpdateUserRequest();
        req.setCenterId(foreign.getId());

        assertThatThrownBy(() -> service.update(target.getId(), req, actor))
            .isInstanceOf(ResourceNotFoundException.class);
        assertThat(target.getCenter()).isSameAs(own);
    }

    // ── create: role rank (403) ─────────────────────────────

    @ParameterizedTest
    @EnumSource(value = UserRole.class, names = {"hospital_supervisor", "region_supervisor", "system_admin"})
    void hospitalSupervisorCannotCreateSupervisorLevelAccounts(UserRole target) {
        Region r = region(tenantA);
        Center own = center(r, tenantA);
        User actor = user(UserRole.hospital_supervisor, own, null, tenantA);
        CreateUserRequest req = create(target, own.getId());
        req.setRegionId(r.getId());

        assertThatThrownBy(() -> service.create(req, actor)).isInstanceOf(AccessDeniedException.class);
        verify(userRepo, never()).save(any());
    }

    @ParameterizedTest
    @EnumSource(value = UserRole.class, names = {"region_supervisor", "system_admin"})
    void regionSupervisorCannotCreateRegionLevelOrAdminAccounts(UserRole target) {
        Region r = region(tenantA);
        User actor = user(UserRole.region_supervisor, null, r, tenantA);
        CreateUserRequest req = create(target, null);
        req.setRegionId(r.getId());

        assertThatThrownBy(() -> service.create(req, actor)).isInstanceOf(AccessDeniedException.class);
        verify(userRepo, never()).save(any());
    }

    @ParameterizedTest
    @EnumSource(value = UserRole.class, names = {"control_room", "technician", "resident", "department", "other",
        "warehouse", "maintenance_manager", "electrical_manager", "operating_company_manager"})
    void nonManagerRolesCannotCreateUsers(UserRole actorRole) {
        Center c = center(region(tenantA), tenantA);
        User actor = user(actorRole, c, null, tenantA);
        assertThatThrownBy(() -> service.create(create(UserRole.technician, c.getId()), actor))
            .isInstanceOf(AccessDeniedException.class);
        verify(userRepo, never()).save(any());
    }

    @Test
    void tenantScopedSystemAdminCannotCreateSystemAdmin() {
        TenantContext.set(tenantA);
        User actor = user(UserRole.system_admin, null, null, null);
        assertThatThrownBy(() -> service.create(create(UserRole.system_admin, null), actor))
            .isInstanceOf(AccessDeniedException.class);
    }

    // ── create: scope (404) ─────────────────────────────────

    @Test
    void hospitalSupervisorCreatingInForeignCenterIsNotFound() {
        TenantContext.set(tenantA);
        Region r = region(tenantA);
        Center own = center(r, tenantA);
        Center foreign = center(r, tenantA);
        User actor = user(UserRole.hospital_supervisor, own, null, tenantA);
        when(tenantSecurity.requireCenter(foreign.getId())).thenReturn(foreign);

        assertThatThrownBy(() -> service.create(create(UserRole.technician, foreign.getId()), actor))
            .isInstanceOf(ResourceNotFoundException.class);
        verify(userRepo, never()).save(any());
    }

    @Test
    void hospitalSupervisorWithoutCenterIdGetsOwnCenterAndTenant() {
        TenantContext.set(tenantA);
        Center own = center(region(tenantA), tenantA);
        User actor = user(UserRole.hospital_supervisor, own, null, tenantA);
        when(tenantSecurity.requireCenter(own.getId())).thenReturn(own);

        service.create(create(UserRole.technician, null), actor);

        User u = saved();
        assertThat(u.getCenter()).isSameAs(own);
        assertThat(u.getTenantId()).isEqualTo(tenantA);
    }

    @Test
    void hospitalSupervisorCreatingOtherRoleGetsOwnCenter() {
        TenantContext.set(tenantA);
        Center own = center(region(tenantA), tenantA);
        User actor = user(UserRole.hospital_supervisor, own, null, tenantA);
        when(tenantSecurity.requireCenter(own.getId())).thenReturn(own);

        service.create(create(UserRole.other, null), actor);

        assertThat(saved().getCenter()).isSameAs(own);
    }

    @Test
    void hospitalSupervisorCannotSetARegion() {
        TenantContext.set(tenantA);
        Region r = region(tenantA);
        Center own = center(r, tenantA);
        User actor = user(UserRole.hospital_supervisor, own, null, tenantA);
        when(tenantSecurity.requireCenter(own.getId())).thenReturn(own);
        when(tenantSecurity.requireRegion(r.getId())).thenReturn(r);
        CreateUserRequest req = create(UserRole.technician, own.getId());
        req.setRegionId(r.getId());

        assertThatThrownBy(() -> service.create(req, actor)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void hospitalSupervisorWithoutAnyCenterCannotCreate() {
        TenantContext.set(tenantA);
        User actor = user(UserRole.hospital_supervisor, null, null, tenantA);
        assertThatThrownBy(() -> service.create(create(UserRole.technician, null), actor))
            .isInstanceOf(IllegalArgumentException.class);
        verify(userRepo, never()).save(any());
    }

    @Test
    void regionSupervisorCreatingInCenterOfOtherRegionIsNotFound() {
        TenantContext.set(tenantA);
        Region own = region(tenantA);
        Center foreign = center(region(tenantA), tenantA);
        User actor = user(UserRole.region_supervisor, null, own, tenantA);
        when(tenantSecurity.requireCenter(foreign.getId())).thenReturn(foreign);

        assertThatThrownBy(() -> service.create(create(UserRole.technician, foreign.getId()), actor))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void regionSupervisorCannotUseForeignRegionId() {
        TenantContext.set(tenantA);
        Region own = region(tenantA);
        Region foreign = region(tenantA);
        Center c = center(own, tenantA);
        User actor = user(UserRole.region_supervisor, null, own, tenantA);
        when(tenantSecurity.requireCenter(c.getId())).thenReturn(c);
        when(tenantSecurity.requireRegion(foreign.getId())).thenReturn(foreign);
        CreateUserRequest req = create(UserRole.technician, c.getId());
        req.setRegionId(foreign.getId());

        assertThatThrownBy(() -> service.create(req, actor)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void regionSupervisorCreatesInOwnRegion() {
        TenantContext.set(tenantA);
        Region own = region(tenantA);
        Center c = center(own, tenantA);
        User actor = user(UserRole.region_supervisor, null, own, tenantA);
        when(tenantSecurity.requireCenter(c.getId())).thenReturn(c);

        service.create(create(UserRole.technician, c.getId()), actor);

        assertThat(saved().getCenter()).isSameAs(c);
    }

    @Test
    void regionSupervisorCannotCreateUserWithoutCenter() {
        TenantContext.set(tenantA);
        Region own = region(tenantA);
        User actor = user(UserRole.region_supervisor, null, own, tenantA);
        assertThatThrownBy(() -> service.create(create(UserRole.other, null), actor))
            .isInstanceOf(IllegalArgumentException.class);
        verify(userRepo, never()).save(any());
    }

    // ── create: tenant (SEC-A) ──────────────────────────────

    @Test
    void platformAdminCreatingUserInTenantBCenterStoresTenantB() {
        Region rb = region(tenantB);
        Center cb = center(rb, tenantB);
        User admin = user(UserRole.system_admin, null, null, null);
        when(tenantSecurity.requireCenter(cb.getId())).thenReturn(cb);

        service.create(create(UserRole.technician, cb.getId()), admin);

        assertThat(saved().getTenantId()).isEqualTo(tenantB);
        verify(planEnforcement).checkUserLimit(tenantB, 1);
    }

    @Test
    void platformAdminCreatingSystemAdminKeepsTenantNull() {
        User admin = user(UserRole.system_admin, null, null, null);
        service.create(create(UserRole.system_admin, null), admin);
        assertThat(saved().getTenantId()).isNull();
    }

    @Test
    void tenantAdminUsesContextTenantAndIgnoresRequestedTenant() {
        TenantContext.set(tenantA);
        Center c = center(region(tenantA), tenantA);
        User admin = user(UserRole.system_admin, null, null, null);
        when(tenantSecurity.requireCenter(c.getId())).thenReturn(c);
        CreateUserRequest req = create(UserRole.technician, c.getId());
        req.setTenantId(tenantB);

        service.create(req, admin);

        assertThat(saved().getTenantId()).isEqualTo(tenantA);
    }

    @Test
    void platformAdminWithMixedTenantParentsIsRejected() {
        Region ra = region(tenantA);
        Center cb = center(region(tenantB), tenantB);
        User admin = user(UserRole.system_admin, null, null, null);
        when(tenantSecurity.requireCenter(cb.getId())).thenReturn(cb);
        when(tenantSecurity.requireRegion(ra.getId())).thenReturn(ra);
        CreateUserRequest req = create(UserRole.technician, cb.getId());
        req.setRegionId(ra.getId());

        assertThatThrownBy(() -> service.create(req, admin)).isInstanceOf(IllegalArgumentException.class);
        verify(userRepo, never()).save(any());
    }

    @Test
    void platformAdminWithoutAnchorNeedsTenantId() {
        User admin = user(UserRole.system_admin, null, null, null);
        assertThatThrownBy(() -> service.create(create(UserRole.other, null), admin))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("tenantId");
        verify(userRepo, never()).save(any());
    }

    @Test
    void platformAdminWithoutAnchorUsesExistingRequestedTenant() {
        User admin = user(UserRole.system_admin, null, null, null);
        when(tenantRepo.existsById(tenantB)).thenReturn(true);
        CreateUserRequest req = create(UserRole.other, null);
        req.setTenantId(tenantB);

        service.create(req, admin);

        assertThat(saved().getTenantId()).isEqualTo(tenantB);
    }

    @Test
    void centerFromAnotherRegionThanTheGivenRegionIsRejected() {
        Region r1 = region(tenantA);
        Region r2 = region(tenantA);
        Center c = center(r1, tenantA);
        User admin = user(UserRole.system_admin, null, null, null);
        when(tenantSecurity.requireCenter(c.getId())).thenReturn(c);
        when(tenantSecurity.requireRegion(r2.getId())).thenReturn(r2);
        CreateUserRequest req = create(UserRole.technician, c.getId());
        req.setRegionId(r2.getId());

        assertThatThrownBy(() -> service.create(req, admin)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void controlRoomOutsideTheActorsCenterIsNotFound() {
        TenantContext.set(tenantA);
        Region r = region(tenantA);
        Center own = center(r, tenantA);
        Center other = center(r, tenantA);
        ControlRoom foreignRoom = room(other, tenantA);
        User actor = user(UserRole.hospital_supervisor, own, null, tenantA);
        when(tenantSecurity.requireCenter(own.getId())).thenReturn(own);
        when(tenantSecurity.requireControlRoom(foreignRoom.getId())).thenReturn(foreignRoom);
        CreateUserRequest req = create(UserRole.resident, own.getId());
        req.setControlRoomId(foreignRoom.getId());

        assertThatThrownBy(() -> service.create(req, actor)).isInstanceOf(ResourceNotFoundException.class);
        verify(userRepo, never()).save(any());
    }

    @Test
    void controlRoomOfOwnCenterIsLinked() {
        TenantContext.set(tenantA);
        Center own = center(region(tenantA), tenantA);
        ControlRoom cr = room(own, tenantA);
        User actor = user(UserRole.hospital_supervisor, own, null, tenantA);
        when(tenantSecurity.requireCenter(own.getId())).thenReturn(own);
        when(tenantSecurity.requireControlRoom(cr.getId())).thenReturn(cr);
        CreateUserRequest req = create(UserRole.resident, own.getId());
        req.setControlRoomIds(List.of(cr.getId()));

        service.create(req, actor);

        verify(accessRepo).save(any(UserControlRoomAccess.class));
    }

    // ── update / toggle: scope ──────────────────────────────

    @Test
    void hospitalSupervisorCannotUpdateOrToggleTechnicianOfAnotherCenter() {
        Region r = region(tenantA);
        Center own = center(r, tenantA);
        Center other = center(r, tenantA);
        User actor = user(UserRole.hospital_supervisor, own, null, tenantA);
        User target = user(UserRole.technician, other, null, tenantA);
        when(tenantSecurity.requireUser(target.getId())).thenReturn(target);
        UpdateUserRequest req = new UpdateUserRequest();
        req.setFullName("Hacked");

        assertThatThrownBy(() -> service.update(target.getId(), req, actor)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.toggleActive(target.getId(), false, actor)).isInstanceOf(ResourceNotFoundException.class);
        assertThat(target.isActive()).isTrue();
        verify(userRepo, never()).save(any());
    }

    @Test
    void hospitalSupervisorCanUpdateAndToggleTechnicianOfOwnCenter() {
        Center own = center(region(tenantA), tenantA);
        User actor = user(UserRole.hospital_supervisor, own, null, tenantA);
        User target = user(UserRole.technician, own, null, tenantA);
        when(tenantSecurity.requireUser(target.getId())).thenReturn(target);
        UpdateUserRequest req = new UpdateUserRequest();
        req.setFullName("Renamed");

        service.update(target.getId(), req, actor);
        service.toggleActive(target.getId(), false, actor);

        assertThat(target.getFullName()).isEqualTo("Renamed");
        assertThat(target.isActive()).isFalse();
    }

    @Test
    void hospitalSupervisorCannotManagePeerSupervisor() {
        Center own = center(region(tenantA), tenantA);
        User actor = user(UserRole.hospital_supervisor, own, null, tenantA);
        User peer = user(UserRole.hospital_supervisor, own, null, tenantA);
        when(tenantSecurity.requireUser(peer.getId())).thenReturn(peer);
        assertThatThrownBy(() -> service.toggleActive(peer.getId(), false, actor)).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void regionSupervisorCannotManageUserOfOtherRegion() {
        Region own = region(tenantA);
        Center foreign = center(region(tenantA), tenantA);
        User actor = user(UserRole.region_supervisor, null, own, tenantA);
        User target = user(UserRole.technician, foreign, null, tenantA);
        when(tenantSecurity.requireUser(target.getId())).thenReturn(target);
        assertThatThrownBy(() -> service.toggleActive(target.getId(), false, actor)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void regionSupervisorCannotManagePeerOrAdmin() {
        Region own = region(tenantA);
        User actor = user(UserRole.region_supervisor, null, own, tenantA);
        User peer = user(UserRole.region_supervisor, null, own, tenantA);
        when(tenantSecurity.requireUser(peer.getId())).thenReturn(peer);
        assertThatThrownBy(() -> service.toggleActive(peer.getId(), false, actor)).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void regionSupervisorMovingUserToCenterOfOtherRegionIsNotFound() {
        Region own = region(tenantA);
        Center mine = center(own, tenantA);
        Center foreign = center(region(tenantA), tenantA);
        User actor = user(UserRole.region_supervisor, null, own, tenantA);
        User target = user(UserRole.technician, mine, null, tenantA);
        when(tenantSecurity.requireUser(target.getId())).thenReturn(target);
        when(tenantSecurity.requireCenter(foreign.getId())).thenReturn(foreign);
        UpdateUserRequest req = new UpdateUserRequest();
        req.setCenterId(foreign.getId());

        assertThatThrownBy(() -> service.update(target.getId(), req, actor)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void roleChangeToCenterRoleWithoutCenterIsRejected() {
        User admin = user(UserRole.system_admin, null, null, null);
        User target = user(UserRole.other, null, null, tenantA);
        when(tenantSecurity.requireUser(target.getId())).thenReturn(target);
        UpdateUserRequest req = new UpdateUserRequest();
        req.setRole(UserRole.technician);

        assertThatThrownBy(() -> service.update(target.getId(), req, admin)).isInstanceOf(IllegalArgumentException.class);
        assertThat(target.getRole()).isEqualTo(UserRole.other);
    }

    @Test
    void unrelatedUpdateOfLegacyUserWithoutCenterStillWorks() {
        User admin = user(UserRole.system_admin, null, null, null);
        User target = user(UserRole.technician, null, null, tenantA);
        when(tenantSecurity.requireUser(target.getId())).thenReturn(target);
        UpdateUserRequest req = new UpdateUserRequest();
        req.setPhone("0500000000");

        service.update(target.getId(), req, admin);
        assertThat(target.getPhone()).isEqualTo("0500000000");
    }

    @Test
    void updateRejectsEmailOfAnotherUser() {
        User admin = user(UserRole.system_admin, null, null, null);
        User target = user(UserRole.technician, center(region(tenantA), tenantA), null, tenantA);
        target.setEmail("old@x.com");
        when(tenantSecurity.requireUser(target.getId())).thenReturn(target);
        when(userRepo.existsByEmailAndDeletedAtIsNull("taken@x.com")).thenReturn(true);
        UpdateUserRequest req = new UpdateUserRequest();
        req.setEmail("taken@x.com");

        assertThatThrownBy(() -> service.update(target.getId(), req, admin)).isInstanceOf(IllegalArgumentException.class);
        assertThat(target.getEmail()).isEqualTo("old@x.com");
    }

    @Test
    void cannotDeactivateOwnAccount() {
        User admin = user(UserRole.system_admin, null, null, null);
        when(tenantSecurity.requireUser(admin.getId())).thenReturn(admin);
        assertThatThrownBy(() -> service.toggleActive(admin.getId(), false, admin)).isInstanceOf(AccessDeniedException.class);
        assertThat(admin.isActive()).isTrue();
    }

    // ── grant control-room access (SEC-C) ───────────────────

    @Test
    void hospitalSupervisorGrantsRoomOfOwnCenterToOwnUser() {
        Center own = center(region(tenantA), tenantA);
        ControlRoom cr = room(own, tenantA);
        User actor = user(UserRole.hospital_supervisor, own, null, tenantA);
        User target = user(UserRole.resident, own, null, tenantA);
        when(tenantSecurity.requireUser(target.getId())).thenReturn(target);
        when(tenantSecurity.requireControlRoom(cr.getId())).thenReturn(cr);

        service.grantControlRoomAccess(target.getId(), cr.getId(), actor);

        verify(accessRepo).save(any(UserControlRoomAccess.class));
    }

    @Test
    void repeatedGrantIsIdempotent() {
        Center own = center(region(tenantA), tenantA);
        ControlRoom cr = room(own, tenantA);
        User actor = user(UserRole.hospital_supervisor, own, null, tenantA);
        User target = user(UserRole.resident, own, null, tenantA);
        when(tenantSecurity.requireUser(target.getId())).thenReturn(target);
        when(tenantSecurity.requireControlRoom(cr.getId())).thenReturn(cr);
        when(accessRepo.existsByUserIdAndControlRoomId(target.getId(), cr.getId())).thenReturn(true);

        service.grantControlRoomAccess(target.getId(), cr.getId(), actor);

        verify(accessRepo, never()).save(any());
    }

    @Test
    void hospitalSupervisorCannotGrantRoomOfAnotherCenter() {
        Region r = region(tenantA);
        Center own = center(r, tenantA);
        Center other = center(r, tenantA);
        ControlRoom foreign = room(other, tenantA);
        User actor = user(UserRole.hospital_supervisor, own, null, tenantA);
        User target = user(UserRole.resident, own, null, tenantA);
        when(tenantSecurity.requireUser(target.getId())).thenReturn(target);
        when(tenantSecurity.requireControlRoom(foreign.getId())).thenReturn(foreign);

        assertThatThrownBy(() -> service.grantControlRoomAccess(target.getId(), foreign.getId(), actor))
            .isInstanceOf(ResourceNotFoundException.class);
        verify(accessRepo, never()).save(any());
    }

    @Test
    void hospitalSupervisorCannotGrantToARegionSupervisor() {
        Region r = region(tenantA);
        Center own = center(r, tenantA);
        ControlRoom cr = room(own, tenantA);
        User actor = user(UserRole.hospital_supervisor, own, null, tenantA);
        User boss = user(UserRole.region_supervisor, null, r, tenantA);
        when(tenantSecurity.requireUser(boss.getId())).thenReturn(boss);

        assertThatThrownBy(() -> service.grantControlRoomAccess(boss.getId(), cr.getId(), actor))
            .isInstanceOf(ResourceNotFoundException.class); // not in the actor's center, so invisible
        verify(accessRepo, never()).save(any());
    }

    @Test
    void hospitalSupervisorCannotGrantToPeerSupervisorOfSameCenter() {
        Center own = center(region(tenantA), tenantA);
        ControlRoom cr = room(own, tenantA);
        User actor = user(UserRole.hospital_supervisor, own, null, tenantA);
        User peer = user(UserRole.hospital_supervisor, own, null, tenantA);
        when(tenantSecurity.requireUser(peer.getId())).thenReturn(peer);

        assertThatThrownBy(() -> service.grantControlRoomAccess(peer.getId(), cr.getId(), actor))
            .isInstanceOf(AccessDeniedException.class);
        verify(accessRepo, never()).save(any());
    }

    @Test
    void crossTenantRoomIsNotFoundForTenantAdmins() {
        TenantContext.set(tenantA);
        Center own = center(region(tenantA), tenantA);
        User actor = user(UserRole.hospital_supervisor, own, null, tenantA);
        User target = user(UserRole.resident, own, null, tenantA);
        UUID foreignRoom = UUID.randomUUID();
        when(tenantSecurity.requireUser(target.getId())).thenReturn(target);
        when(tenantSecurity.requireControlRoom(foreignRoom)).thenThrow(new ResourceNotFoundException("ControlRoom not found"));

        assertThatThrownBy(() -> service.grantControlRoomAccess(target.getId(), foreignRoom, actor))
            .isInstanceOf(ResourceNotFoundException.class);
        verify(accessRepo, never()).save(any());
    }

    @Test
    void platformAdminCannotGrantRoomOfAnotherTenant() {
        Center ca = center(region(tenantA), tenantA);
        ControlRoom roomB = room(center(region(tenantB), tenantB), tenantB);
        User admin = user(UserRole.system_admin, null, null, null);
        User target = user(UserRole.resident, ca, null, tenantA);
        when(tenantSecurity.requireUser(target.getId())).thenReturn(target);
        when(tenantSecurity.requireControlRoom(roomB.getId())).thenReturn(roomB);

        assertThatThrownBy(() -> service.grantControlRoomAccess(target.getId(), roomB.getId(), admin))
            .isInstanceOf(IllegalArgumentException.class);
        verify(accessRepo, never()).save(any());
    }

    @Test
    void regionSupervisorCannotGrantRoomOutsideRegion() {
        Region own = region(tenantA);
        Center mine = center(own, tenantA);
        ControlRoom foreign = room(center(region(tenantA), tenantA), tenantA);
        User actor = user(UserRole.region_supervisor, null, own, tenantA);
        User target = user(UserRole.resident, mine, null, tenantA);
        when(tenantSecurity.requireUser(target.getId())).thenReturn(target);
        when(tenantSecurity.requireControlRoom(foreign.getId())).thenReturn(foreign);

        assertThatThrownBy(() -> service.grantControlRoomAccess(target.getId(), foreign.getId(), actor))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void regionSupervisorCannotGrantRoomOfOtherCenterToUserOfMyCenter() {
        Region own = region(tenantA);
        Center mine = center(own, tenantA);
        Center sibling = center(own, tenantA);
        ControlRoom siblingRoom = room(sibling, tenantA);
        User actor = user(UserRole.region_supervisor, null, own, tenantA);
        User target = user(UserRole.resident, mine, null, tenantA);
        when(tenantSecurity.requireUser(target.getId())).thenReturn(target);
        when(tenantSecurity.requireControlRoom(siblingRoom.getId())).thenReturn(siblingRoom);

        assertThatThrownBy(() -> service.grantControlRoomAccess(target.getId(), siblingRoom.getId(), actor))
            .isInstanceOf(AccessDeniedException.class);
        verify(accessRepo, never()).save(any());
    }

    @Test
    void systemAdminMayGrantRoomOfAnotherCenterInSameTenant() {
        Region r = region(tenantA);
        Center mine = center(r, tenantA);
        ControlRoom other = room(center(r, tenantA), tenantA);
        User admin = user(UserRole.system_admin, null, null, null);
        User target = user(UserRole.resident, mine, null, tenantA);
        when(tenantSecurity.requireUser(target.getId())).thenReturn(target);
        when(tenantSecurity.requireControlRoom(other.getId())).thenReturn(other);

        service.grantControlRoomAccess(target.getId(), other.getId(), admin);

        verify(accessRepo).save(any(UserControlRoomAccess.class));
    }
}
