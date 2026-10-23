package com.smms.service;

import com.smms.entity.Center;
import com.smms.entity.Region;
import com.smms.entity.User;
import com.smms.enums.UserRole;
import com.smms.mapper.UserMapper;
import com.smms.repository.TenantRepository;
import com.smms.repository.UserControlRoomAccessRepository;
import com.smms.repository.UserRepository;
import com.smms.repository.UserSpecs;
import com.smms.security.AdminScopePolicy;
import com.smms.security.AdminScopePolicy.UserListScope;
import com.smms.security.TenantSecurityService;
import com.smms.util.TenantContext;
import jakarta.persistence.criteria.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.UUID;

import static com.smms.support.Fixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** SEC-B: who may list users and what the generated predicate contains. */
@SuppressWarnings({"unchecked", "rawtypes"})
class UserListAuthorizationTest {

    private final UUID tenantA = UUID.randomUUID();
    private final Pageable page = PageRequest.of(0, 20);
    private UserRepository userRepo;
    private UserService service;

    @BeforeEach
    void setUp() {
        userRepo = mock(UserRepository.class);
        service = new UserService(userRepo, mock(UserControlRoomAccessRepository.class),
            mock(PasswordEncoder.class), mock(UserMapper.class), mock(PlanEnforcementService.class),
            mock(TenantSecurityService.class), new AdminScopePolicy(mock(TenantRepository.class)), new UserAccountValidator());
        when(userRepo.findAll(any(Specification.class), any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));
    }

    @AfterEach
    void clear() { TenantContext.clear(); }

    // ── service level ───────────────────────────────────────

    @ParameterizedTest
    @EnumSource(value = UserRole.class, names = {"control_room", "technician", "resident", "department", "other",
        "warehouse", "maintenance_manager", "electrical_manager", "operating_company_manager"})
    void nonSupervisorRolesCannotListUsers(UserRole role) {
        User actor = user(role, center(region(tenantA), tenantA), null, tenantA);
        assertThatThrownBy(() -> service.list(actor, null, null, page)).isInstanceOf(AccessDeniedException.class);
        verify(userRepo, never()).findAll(any(Specification.class), any(Pageable.class));
    }

    @Test
    void supervisorsWithoutCenterOrRegionCannotListUsers() {
        assertThatThrownBy(() -> service.list(user(UserRole.hospital_supervisor, null, null, tenantA), null, null, page))
            .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.list(user(UserRole.region_supervisor, null, null, tenantA), null, null, page))
            .isInstanceOf(AccessDeniedException.class);
        verify(userRepo, never()).findAll(any(Specification.class), any(Pageable.class));
    }

    @ParameterizedTest
    @EnumSource(value = UserRole.class, names = {"system_admin", "region_supervisor", "hospital_supervisor"})
    void managerRolesReachTheRepository(UserRole role) {
        Region r = region(tenantA);
        User actor = user(role, role == UserRole.hospital_supervisor ? center(r, tenantA) : null,
            role == UserRole.region_supervisor ? r : null, role == UserRole.system_admin ? null : tenantA);
        service.list(actor, null, null, page);
        verify(userRepo).findAll(any(Specification.class), eq(page));
    }

    @Test
    void controllerRestrictsUserListToManagerRoles() throws Exception {
        PreAuthorize pa = com.smms.controller.UserController.class
            .getMethod("list", UUID.class, UserRole.class, int.class, int.class, List.class, User.class)
            .getAnnotation(PreAuthorize.class);
        assertThat(pa.value()).contains("system_admin", "region_supervisor", "hospital_supervisor")
            .doesNotContain("control_room", "technician");
    }

    @Test
    void controllerRestrictsAssignableTechniciansToAssignRoles() throws Exception {
        PreAuthorize pa = com.smms.controller.WorkOrderController.class
            .getMethod("assignableTechnicians", UUID.class, User.class).getAnnotation(PreAuthorize.class);
        assertThat(pa.value()).contains("control_room", "maintenance_manager", "system_admin")
            .doesNotContain("technician", "resident", "hospital_supervisor");
    }

    // ── predicate level ─────────────────────────────────────

    private static class Ctx {
        CriteriaBuilder cb = mock(CriteriaBuilder.class);
        Root root = mock(Root.class);
        Path tenantPath = mock(Path.class), deletedPath = mock(Path.class), rolePath = mock(Path.class);
        Join centerJoin = mock(Join.class), regionJoin = mock(Join.class), centerRegionJoin = mock(Join.class);
        Path centerId = mock(Path.class), regionId = mock(Path.class), centerRegionId = mock(Path.class);
        Predicate any = mock(Predicate.class);

        Ctx() {
            when(root.get("tenantId")).thenReturn(tenantPath);
            when(root.get("deletedAt")).thenReturn(deletedPath);
            when(root.get("role")).thenReturn(rolePath);
            when(root.join("center", JoinType.LEFT)).thenReturn(centerJoin);
            when(root.join("region", JoinType.LEFT)).thenReturn(regionJoin);
            when(centerJoin.join("region", JoinType.LEFT)).thenReturn(centerRegionJoin);
            when(centerJoin.get("id")).thenReturn(centerId);
            when(regionJoin.get("id")).thenReturn(regionId);
            when(centerRegionJoin.get("id")).thenReturn(centerRegionId);
            when(rolePath.in(any(java.util.Collection.class))).thenReturn(any);
            when(cb.equal(any(Expression.class), any(Object.class))).thenReturn(any);
            when(cb.isNull(any())).thenReturn(any);
            when(cb.not(any())).thenReturn(any);
            when(cb.or(any(Predicate[].class))).thenReturn(any);
            when(cb.and(any(Predicate[].class))).thenReturn(any);
            when(cb.disjunction()).thenReturn(any);
        }

        void apply(Specification<User> spec) { spec.toPredicate(root, mock(CriteriaQuery.class), cb); }
    }

    private static final AdminScopePolicy POLICY = new AdminScopePolicy(mock(TenantRepository.class));

    @Test
    void noneScopeMatchesNothing() {
        Ctx c = new Ctx();
        c.apply(UserSpecs.listFor(POLICY.resolveUserListScope(user(UserRole.technician, null, null, tenantA)),
            tenantA, null, null));
        verify(c.cb).disjunction();
        verify(c.cb, never()).equal(any(Expression.class), any(Object.class));
    }

    @Test
    void hospitalSupervisorIsRestrictedToOwnCenterAndTenantAndHidesAdminRoles() {
        Center own = center(region(tenantA), tenantA);
        UserListScope scope = POLICY.resolveUserListScope(user(UserRole.hospital_supervisor, own, null, tenantA));
        Ctx c = new Ctx();
        c.apply(UserSpecs.listFor(scope, tenantA, null, null));
        verify(c.cb).equal(c.tenantPath, tenantA);
        verify(c.cb).equal(c.centerId, own.getId());
        ArgumentCaptor<java.util.Collection> roles = ArgumentCaptor.forClass(java.util.Collection.class);
        verify(c.rolePath).in(roles.capture());
        assertThat(roles.getValue()).contains(UserRole.system_admin, UserRole.region_supervisor);
    }

    @Test
    void callerCenterFilterOnlyNarrowsWithinTheScope() {
        Center own = center(region(tenantA), tenantA);
        UUID foreign = UUID.randomUUID();
        UserListScope scope = POLICY.resolveUserListScope(user(UserRole.hospital_supervisor, own, null, tenantA));
        Ctx c = new Ctx();
        c.apply(UserSpecs.listFor(scope, tenantA, foreign, null));
        // both predicates are ANDed: scope center and requested center can never both match a foreign id
        verify(c.cb).equal(c.centerId, own.getId());
        verify(c.cb).equal(c.centerId, foreign);
    }

    @Test
    void regionSupervisorIsRestrictedToOwnRegionAndFiltersApply() {
        Region own = region(tenantA);
        UserListScope scope = POLICY.resolveUserListScope(user(UserRole.region_supervisor, null, own, tenantA));
        Ctx c = new Ctx();
        c.apply(UserSpecs.listFor(scope, tenantA, null, UserRole.technician));
        verify(c.cb).equal(c.regionId, own.getId());
        verify(c.cb).equal(c.centerRegionId, own.getId());
        verify(c.cb).or(any(Predicate.class), any(Predicate.class));
        verify(c.cb).equal(c.rolePath, UserRole.technician);
    }

    @Test
    void tenantAdminGetsTenantPredicateAndPlatformAdminDoesNot() {
        UserListScope all = POLICY.resolveUserListScope(user(UserRole.system_admin, null, null, null));
        Ctx tenantCtx = new Ctx();
        tenantCtx.apply(UserSpecs.listFor(all, tenantA, null, null));
        verify(tenantCtx.cb).equal(tenantCtx.tenantPath, tenantA);

        Ctx platform = new Ctx();
        platform.apply(UserSpecs.listFor(all, null, null, null));
        verify(platform.cb, never()).equal(eq(platform.tenantPath), any(Object.class));
        verify(platform.cb, never()).disjunction();
    }
}
