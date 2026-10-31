package com.smms.service;

import com.smms.dto.request.CreateCenterRequest;
import com.smms.dto.request.UpdateCenterRequest;
import com.smms.entity.Center;
import com.smms.entity.Region;
import com.smms.entity.User;
import com.smms.enums.UserRole;
import com.smms.exception.PlanLimitExceededException;
import com.smms.exception.ResourceNotFoundException;
import com.smms.repository.CenterRepository;
import com.smms.repository.TenantRepository;
import com.smms.security.AdminScopePolicy;
import com.smms.security.TenantSecurityService;
import com.smms.util.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.access.AccessDeniedException;

import java.util.UUID;

import static com.smms.support.Fixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class CenterServiceScopeTest {

    private final UUID tA = UUID.randomUUID();
    private final UUID tB = UUID.randomUUID();
    private CenterRepository centerRepo;
    private TenantSecurityService tenantSecurity;
    private PlanEnforcementService plan;
    private CenterService service;

    @BeforeEach
    void setUp() {
        centerRepo = mock(CenterRepository.class);
        tenantSecurity = mock(TenantSecurityService.class);
        plan = mock(PlanEnforcementService.class);
        service = new CenterService(centerRepo, plan, tenantSecurity, new AdminScopePolicy(mock(TenantRepository.class)));
        when(centerRepo.save(any(Center.class))).thenAnswer(i -> i.getArgument(0));
    }

    @AfterEach
    void clear() { TenantContext.clear(); }

    private CreateCenterRequest req(UUID regionId) {
        CreateCenterRequest r = new CreateCenterRequest();
        r.setName("C");
        r.setNameAr("C");
        r.setCode("c1");
        r.setRegionId(regionId);
        return r;
    }

    private Center saved() {
        ArgumentCaptor<Center> c = ArgumentCaptor.forClass(Center.class);
        verify(centerRepo).save(c.capture());
        return c.getValue();
    }

    @Test
    void regionSupervisorCannotCreateCenterInAnotherRegion() {
        TenantContext.set(tA);
        Region own = region(tA), foreign = region(tA);
        User actor = user(UserRole.region_supervisor, null, own, tA);
        when(tenantSecurity.requireRegion(foreign.getId())).thenReturn(foreign);

        assertThatThrownBy(() -> service.create(req(foreign.getId()), actor)).isInstanceOf(ResourceNotFoundException.class);
        verify(centerRepo, never()).save(any());
    }

    @Test
    void regionSupervisorCreatesCenterInOwnRegionWithContextTenant() {
        TenantContext.set(tA);
        Region own = region(tA);
        User actor = user(UserRole.region_supervisor, null, own, tA);
        when(tenantSecurity.requireRegion(own.getId())).thenReturn(own);

        service.create(req(own.getId()), actor);

        Center c = saved();
        assertThat(c.getTenantId()).isEqualTo(tA);
        assertThat(c.getCode()).isEqualTo("C1");
        verify(plan).checkCenterLimit(tA);
    }

    @Test
    void platformAdminCreatesCenterWithTenantOfTheRegion() {
        Region rb = region(tB);
        User admin = user(UserRole.system_admin, null, null, null);
        when(tenantSecurity.requireRegion(rb.getId())).thenReturn(rb);

        service.create(req(rb.getId()), admin);

        assertThat(saved().getTenantId()).isEqualTo(tB);
        verify(plan).checkCenterLimit(tB);
    }

    @Test
    void hospitalSupervisorCannotCreateCenters() {
        TenantContext.set(tA);
        Region r = region(tA);
        User actor = user(UserRole.hospital_supervisor, center(r, tA), null, tA);
        when(tenantSecurity.requireRegion(r.getId())).thenReturn(r);
        assertThatThrownBy(() -> service.create(req(r.getId()), actor)).isInstanceOf(ResourceNotFoundException.class);
        verify(centerRepo, never()).save(any());
    }

    @Test
    void planLimitOfTheDerivedTenantStopsCreation() {
        Region rb = region(tB);
        User admin = user(UserRole.system_admin, null, null, null);
        when(tenantSecurity.requireRegion(rb.getId())).thenReturn(rb);
        doThrow(new PlanLimitExceededException("limit")).when(plan).checkCenterLimit(tB);
        assertThatThrownBy(() -> service.create(req(rb.getId()), admin)).isInstanceOf(PlanLimitExceededException.class);
        verify(centerRepo, never()).save(any());
    }

    @Test
    void duplicateCodeIsRejected() {
        TenantContext.set(tA);
        Region own = region(tA);
        User actor = user(UserRole.system_admin, null, null, null);
        when(tenantSecurity.requireRegion(own.getId())).thenReturn(own);
        when(centerRepo.findByCodeAndDeletedAtIsNull("C1")).thenReturn(java.util.Optional.of(new Center()));
        assertThatThrownBy(() -> service.create(req(own.getId()), actor)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void regionSupervisorCannotUpdateCenterOfAnotherRegion() {
        Region own = region(tA);
        Center foreign = center(region(tA), tA);
        User actor = user(UserRole.region_supervisor, null, own, tA);
        when(tenantSecurity.requireCenter(foreign.getId())).thenReturn(foreign);
        UpdateCenterRequest r = new UpdateCenterRequest();
        r.setName("x");
        assertThatThrownBy(() -> service.update(foreign.getId(), r, actor)).isInstanceOf(ResourceNotFoundException.class);
        verify(centerRepo, never()).save(any());
    }

    @Test
    void regionSupervisorCannotMoveCenterToAnotherRegion() {
        Region own = region(tA), other = region(tA);
        Center mine = center(own, tA);
        User actor = user(UserRole.region_supervisor, null, own, tA);
        when(tenantSecurity.requireCenter(mine.getId())).thenReturn(mine);
        UpdateCenterRequest r = new UpdateCenterRequest();
        r.setRegionId(other.getId());
        assertThatThrownBy(() -> service.update(mine.getId(), r, actor)).isInstanceOf(AccessDeniedException.class);
        assertThat(mine.getRegion()).isSameAs(own);
        verify(tenantSecurity, never()).requireRegion(any());
    }

    @Test
    void regionSupervisorMayRenameCenterAndKeepRegion() {
        Region own = region(tA);
        Center mine = center(own, tA);
        User actor = user(UserRole.region_supervisor, null, own, tA);
        when(tenantSecurity.requireCenter(mine.getId())).thenReturn(mine);
        UpdateCenterRequest r = new UpdateCenterRequest();
        r.setName("Renamed");
        r.setRegionId(own.getId());
        service.update(mine.getId(), r, actor);
        assertThat(mine.getName()).isEqualTo("Renamed");
    }

    @Test
    void systemAdminMovesCenterWithinTenantButNotAcrossTenants() {
        Region r1 = region(tA), r2 = region(tA), rb = region(tB);
        Center c = center(r1, tA);
        User admin = user(UserRole.system_admin, null, null, null);
        when(tenantSecurity.requireCenter(c.getId())).thenReturn(c);
        when(tenantSecurity.requireRegion(r2.getId())).thenReturn(r2);
        when(tenantSecurity.requireRegion(rb.getId())).thenReturn(rb);

        UpdateCenterRequest ok = new UpdateCenterRequest();
        ok.setRegionId(r2.getId());
        service.update(c.getId(), ok, admin);
        assertThat(c.getRegion()).isSameAs(r2);

        UpdateCenterRequest bad = new UpdateCenterRequest();
        bad.setRegionId(rb.getId());
        assertThatThrownBy(() -> service.update(c.getId(), bad, admin)).isInstanceOf(IllegalArgumentException.class);
        assertThat(c.getRegion()).isSameAs(r2);
    }
}
