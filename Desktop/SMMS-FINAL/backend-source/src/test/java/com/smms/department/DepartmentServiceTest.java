package com.smms.department;

import com.smms.dto.request.CreateDepartmentRequest;
import com.smms.dto.request.UpdateDepartmentRequest;
import com.smms.dto.response.UserResponse;
import com.smms.entity.*;
import com.smms.enums.UserRole;
import com.smms.exception.ConflictException;
import com.smms.exception.ResourceNotFoundException;
import com.smms.exception.WorkflowException;
import com.smms.mapper.UserMapper;
import com.smms.repository.TenantRepository;
import com.smms.repository.UserRepository;
import com.smms.security.AdminScopePolicy;
import com.smms.security.TenantSecurityService;
import com.smms.support.Fixtures;
import com.smms.util.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.AccessDeniedException;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DepartmentServiceTest {

    private final UUID tenant = UUID.randomUUID();
    private DepartmentCrudRepository repo;
    private TenantSecurityService tenantSecurity;
    private UserRepository userRepo;
    private UserMapper userMapper;
    private DepartmentService service;
    private Region region, otherRegion;
    private Center center, otherCenterSameRegion, otherRegionCenter;

    @BeforeEach
    void setUp() {
        TenantContext.set(tenant);
        repo = mock(DepartmentCrudRepository.class);
        tenantSecurity = mock(TenantSecurityService.class);
        userRepo = mock(UserRepository.class);
        userMapper = mock(UserMapper.class);
        service = new DepartmentService(repo, tenantSecurity, new AdminScopePolicy(mock(TenantRepository.class)),
            userRepo, userMapper);
        region = Fixtures.region(tenant);
        otherRegion = Fixtures.region(tenant);
        center = Fixtures.center(region, tenant);
        otherCenterSameRegion = Fixtures.center(region, tenant);
        otherRegionCenter = Fixtures.center(otherRegion, tenant);
        when(repo.saveAndFlush(any(Department.class))).thenAnswer(i -> i.getArgument(0));
        when(userRepo.save(any(User.class))).thenAnswer(i -> i.getArgument(0));
        when(userMapper.toResponse(any(User.class))).thenAnswer(i -> UserResponse.builder().build());
    }

    @AfterEach
    void clear() { TenantContext.clear(); }

    private User actor(UserRole role, Center c, Region r) { return Fixtures.user(role, c, r, tenant); }

    private CreateDepartmentRequest create(Center c, String code) {
        CreateDepartmentRequest r = new CreateDepartmentRequest();
        r.setCenterId(c.getId());
        r.setName("Radiology");
        r.setNameAr("الأشعة");
        r.setCode(code);
        return r;
    }

    private Department dept(Center c, boolean active) {
        Department d = Department.builder().center(c).name("D").nameAr("D").code("D1").active(active).build();
        d.setId(UUID.randomUUID());
        d.setTenantId(tenant);
        return d;
    }

    @ParameterizedTest
    @EnumSource(value = UserRole.class, names = {"resident", "department", "technician", "warehouse", "other"})
    void everythingIsDeniedForRolesOutsideTheMatrix(UserRole role) {
        User u = actor(role, center, region);
        assertThatThrownBy(() -> service.list(null, null, null, PageRequest.of(0, 10), u)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.get(UUID.randomUUID(), u)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.create(create(center, "X"), u)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.linkUser(UUID.randomUUID(), null, u)).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(repo, userRepo);
    }

    @ParameterizedTest
    @EnumSource(value = UserRole.class, names = {"maintenance_manager", "electrical_manager", "operating_company_manager", "control_room"})
    void readOnlyRolesCannotWrite(UserRole role) {
        User u = actor(role, center, region);
        assertThatThrownBy(() -> service.create(create(center, "X"), u)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.update(UUID.randomUUID(), new UpdateDepartmentRequest(), u)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.deactivate(UUID.randomUUID(), u)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.linkUser(UUID.randomUUID(), null, u)).isInstanceOf(AccessDeniedException.class);
        verify(repo, never()).saveAndFlush(any());
    }

    @Test
    void hospitalSupervisorCreatesInOwnCenterWithTheCentersTenantAndNormalizedCode() {
        when(tenantSecurity.requireCenter(center.getId())).thenReturn(center);
        var r = service.create(create(center, " rad-1 "), actor(UserRole.hospital_supervisor, center, region));
        assertThat(r.getCode()).isEqualTo("RAD-1");
        var cap = org.mockito.ArgumentCaptor.forClass(Department.class);
        verify(repo).saveAndFlush(cap.capture());
        assertThat(cap.getValue().getTenantId()).isEqualTo(tenant);
        assertThat(cap.getValue().isActive()).isTrue();
    }

    @Test
    void hospitalSupervisorOfAnotherCenterAndRegionSupervisorOfAnotherRegionGet404() {
        when(tenantSecurity.requireCenter(center.getId())).thenReturn(center);
        assertThatThrownBy(() -> service.create(create(center, "X"), actor(UserRole.hospital_supervisor, otherCenterSameRegion, region)))
            .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.create(create(center, "X"), actor(UserRole.region_supervisor, null, otherRegion)))
            .isInstanceOf(ResourceNotFoundException.class);
        // a region supervisor of the right region may create in any center of it
        assertThat(service.create(create(center, "X"), actor(UserRole.region_supervisor, null, region))).isNotNull();
    }

    @Test
    void crossTenantCenterIs404FromTheTenantScopedLookup() {
        UUID foreign = UUID.randomUUID();
        when(tenantSecurity.requireCenter(foreign)).thenThrow(new ResourceNotFoundException("Center not found"));
        CreateDepartmentRequest r = create(center, "X");
        r.setCenterId(foreign);
        assertThatThrownBy(() -> service.create(r, actor(UserRole.system_admin, null, null)))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void duplicateCodeInTheCenterIs409ByCheckAndByConstraint() {
        when(tenantSecurity.requireCenter(center.getId())).thenReturn(center);
        User hs = actor(UserRole.hospital_supervisor, center, region);
        when(repo.existsByCenterIdAndCodeIgnoreCase(center.getId(), "DUP")).thenReturn(true);
        assertThatThrownBy(() -> service.create(create(center, "dup"), hs)).isInstanceOf(ConflictException.class);
        when(repo.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("uq"));
        assertThatThrownBy(() -> service.create(create(center, "race"), hs)).isInstanceOf(ConflictException.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void updateCannotMoveACenterAndChecksCodeUniqueness() {
        Department d = dept(center, true);
        when(repo.findOne(any(Specification.class))).thenReturn(java.util.Optional.of(d));
        User hs = actor(UserRole.hospital_supervisor, center, region);
        UpdateDepartmentRequest move = new UpdateDepartmentRequest();
        move.setCenterId(otherCenterSameRegion.getId());
        move.setName("N"); move.setNameAr("N"); move.setCode("D1");
        assertThatThrownBy(() -> service.update(d.getId(), move, hs)).isInstanceOf(WorkflowException.class);

        UpdateDepartmentRequest clash = new UpdateDepartmentRequest();
        clash.setCenterId(center.getId());
        clash.setName("N"); clash.setNameAr("N"); clash.setCode("OTHER");
        when(repo.existsByCenterIdAndCodeIgnoreCaseAndIdNot(center.getId(), "OTHER", d.getId())).thenReturn(true);
        assertThatThrownBy(() -> service.update(d.getId(), clash, hs)).isInstanceOf(ConflictException.class);

        clash.setCode("d1"); // same code, different case: not a clash with itself
        clash.setActive(false);
        var r = service.update(d.getId(), clash, hs);
        assertThat(r.isActive()).isFalse();
        assertThat(r.getCode()).isEqualTo("D1");
    }

    @Test
    @SuppressWarnings("unchecked")
    void invisibleDepartmentIs404() {
        when(repo.findOne(any(Specification.class))).thenReturn(java.util.Optional.empty());
        assertThatThrownBy(() -> service.get(UUID.randomUUID(), actor(UserRole.hospital_supervisor, center, region)))
            .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.deactivate(UUID.randomUUID(), actor(UserRole.system_admin, null, null)))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void deactivateIsOneWayAndRepeatIs422() {
        Department d = dept(center, true);
        when(repo.findOne(any(Specification.class))).thenReturn(java.util.Optional.of(d));
        User hs = actor(UserRole.hospital_supervisor, center, region);
        assertThat(service.deactivate(d.getId(), hs).isActive()).isFalse();
        assertThatThrownBy(() -> service.deactivate(d.getId(), hs)).isInstanceOf(WorkflowException.class);
    }

    // ── user link ──

    private User target(UserRole role, Center c) { return Fixtures.user(role, c, null, tenant); }

    @Test
    @SuppressWarnings("unchecked")
    void linkRequiresSameCenterActiveDepartmentAndManageableUser() {
        User hs = actor(UserRole.hospital_supervisor, center, region);
        User dep = target(UserRole.department, center);
        when(tenantSecurity.requireUser(dep.getId())).thenReturn(dep);
        Department good = dept(center, true);
        when(repo.findAll(any(Specification.class))).thenReturn(List.of(good));
        service.linkUser(dep.getId(), good.getId(), hs);
        assertThat(dep.getDepartment()).isSameAs(good);

        Department elsewhere = dept(otherCenterSameRegion, true);
        when(repo.findAll(any(Specification.class))).thenReturn(List.of(elsewhere));
        assertThatThrownBy(() -> service.linkUser(dep.getId(), elsewhere.getId(), hs)).isInstanceOf(WorkflowException.class);

        Department inactive = dept(center, false);
        when(repo.findAll(any(Specification.class))).thenReturn(List.of(inactive));
        assertThatThrownBy(() -> service.linkUser(dep.getId(), inactive.getId(), hs)).isInstanceOf(WorkflowException.class);

        when(repo.findAll(any(Specification.class))).thenReturn(List.of()); // other tenant or deleted
        assertThatThrownBy(() -> service.linkUser(dep.getId(), UUID.randomUUID(), hs)).isInstanceOf(ResourceNotFoundException.class);
        assertThat(dep.getDepartment()).isSameAs(good); // failed attempts changed nothing
    }

    @Test
    void linkIsScopedByUserAdministrationRules() {
        User hsOther = actor(UserRole.hospital_supervisor, otherCenterSameRegion, region);
        User dep = target(UserRole.department, center);
        when(tenantSecurity.requireUser(dep.getId())).thenReturn(dep);
        assertThatThrownBy(() -> service.linkUser(dep.getId(), null, hsOther)).isInstanceOf(ResourceNotFoundException.class);
        // rank: a hospital supervisor cannot manage a peer/higher account
        User peer = target(UserRole.hospital_supervisor, center);
        when(tenantSecurity.requireUser(peer.getId())).thenReturn(peer);
        assertThatThrownBy(() -> service.linkUser(peer.getId(), null, actor(UserRole.hospital_supervisor, center, region)))
            .isInstanceOf(AccessDeniedException.class);
        UUID missing = UUID.randomUUID();
        when(tenantSecurity.requireUser(missing)).thenThrow(new ResourceNotFoundException("User not found"));
        assertThatThrownBy(() -> service.linkUser(missing, null, actor(UserRole.system_admin, null, null)))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void unlinkWithNull() {
        User hs = actor(UserRole.hospital_supervisor, center, region);
        User dep = target(UserRole.department, center);
        dep.setDepartment(dept(center, true));
        when(tenantSecurity.requireUser(dep.getId())).thenReturn(dep);
        service.linkUser(dep.getId(), null, hs);
        assertThat(dep.getDepartment()).isNull();
    }
}
