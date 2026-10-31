package com.smms.service;

import com.smms.dto.request.CreateControlRoomRequest;
import com.smms.dto.request.UpdateControlRoomRequest;
import com.smms.entity.Center;
import com.smms.entity.ControlRoom;
import com.smms.entity.Region;
import com.smms.entity.User;
import com.smms.enums.ControlRoomType;
import com.smms.enums.UserRole;
import com.smms.exception.ResourceNotFoundException;
import com.smms.repository.ControlRoomRepository;
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

class ControlRoomServiceScopeTest {

    private final UUID tA = UUID.randomUUID();
    private final UUID tB = UUID.randomUUID();
    private ControlRoomRepository repo;
    private TenantSecurityService tenantSecurity;
    private ControlRoomService service;

    @BeforeEach
    void setUp() {
        repo = mock(ControlRoomRepository.class);
        tenantSecurity = mock(TenantSecurityService.class);
        service = new ControlRoomService(repo, tenantSecurity, new AdminScopePolicy(mock(TenantRepository.class)));
        when(repo.save(any(ControlRoom.class))).thenAnswer(i -> i.getArgument(0));
    }

    @AfterEach
    void clear() { TenantContext.clear(); }

    private CreateControlRoomRequest req(UUID centerId) {
        CreateControlRoomRequest r = new CreateControlRoomRequest();
        r.setName("CR");
        r.setNameAr("CR");
        r.setCode("cr1");
        r.setType(ControlRoomType.hospital);
        r.setCenterId(centerId);
        return r;
    }

    @Test
    void hospitalSupervisorCannotCreateRoomInAnotherCenter() {
        TenantContext.set(tA);
        Region r = region(tA);
        Center own = center(r, tA), other = center(r, tA);
        User actor = user(UserRole.hospital_supervisor, own, null, tA);
        when(tenantSecurity.requireCenter(other.getId())).thenReturn(other);
        assertThatThrownBy(() -> service.create(req(other.getId()), actor)).isInstanceOf(ResourceNotFoundException.class);
        verify(repo, never()).save(any());
    }

    @Test
    void hospitalSupervisorCreatesRoomInOwnCenterWithTenantOfTheCenter() {
        TenantContext.set(tA);
        Center own = center(region(tA), tA);
        User actor = user(UserRole.hospital_supervisor, own, null, tA);
        when(tenantSecurity.requireCenter(own.getId())).thenReturn(own);
        service.create(req(own.getId()), actor);
        ArgumentCaptor<ControlRoom> c = ArgumentCaptor.forClass(ControlRoom.class);
        verify(repo).save(c.capture());
        assertThat(c.getValue().getTenantId()).isEqualTo(tA);
        assertThat(c.getValue().getCode()).isEqualTo("CR1");
    }

    @Test
    void regionSupervisorCannotCreateRoomOutsideRegion() {
        TenantContext.set(tA);
        Center foreign = center(region(tA), tA);
        User actor = user(UserRole.region_supervisor, null, region(tA), tA);
        when(tenantSecurity.requireCenter(foreign.getId())).thenReturn(foreign);
        assertThatThrownBy(() -> service.create(req(foreign.getId()), actor)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void platformAdminRoomGetsTenantOfItsCenter() {
        Center cb = center(region(tB), tB);
        User admin = user(UserRole.system_admin, null, null, null);
        when(tenantSecurity.requireCenter(cb.getId())).thenReturn(cb);
        service.create(req(cb.getId()), admin);
        ArgumentCaptor<ControlRoom> c = ArgumentCaptor.forClass(ControlRoom.class);
        verify(repo).save(c.capture());
        assertThat(c.getValue().getTenantId()).isEqualTo(tB);
    }

    @Test
    void hospitalSupervisorCannotUpdateRoomOfAnotherCenter() {
        Region r = region(tA);
        Center own = center(r, tA), other = center(r, tA);
        ControlRoom foreign = room(other, tA);
        User actor = user(UserRole.hospital_supervisor, own, null, tA);
        when(tenantSecurity.requireControlRoom(foreign.getId())).thenReturn(foreign);
        UpdateControlRoomRequest u = new UpdateControlRoomRequest();
        u.setName("x");
        assertThatThrownBy(() -> service.update(foreign.getId(), u, actor)).isInstanceOf(ResourceNotFoundException.class);
        verify(repo, never()).save(any());
    }

    @Test
    void hospitalSupervisorCannotMoveRoomToAnotherCenter() {
        Region r = region(tA);
        Center own = center(r, tA), other = center(r, tA);
        ControlRoom mine = room(own, tA);
        User actor = user(UserRole.hospital_supervisor, own, null, tA);
        when(tenantSecurity.requireControlRoom(mine.getId())).thenReturn(mine);
        when(tenantSecurity.requireCenter(other.getId())).thenReturn(other);
        UpdateControlRoomRequest u = new UpdateControlRoomRequest();
        u.setCenterId(other.getId());
        // the target center is outside the hospital supervisor's scope, so it is not even visible
        assertThatThrownBy(() -> service.update(mine.getId(), u, actor)).isInstanceOf(ResourceNotFoundException.class);
        assertThat(mine.getCenter()).isSameAs(own);
    }

    @Test
    void regionSupervisorMovesRoomBetweenOwnCentersButNotOutOfRegion() {
        Region own = region(tA);
        Center c1 = center(own, tA), c2 = center(own, tA), foreign = center(region(tA), tA);
        ControlRoom cr = room(c1, tA);
        User actor = user(UserRole.region_supervisor, null, own, tA);
        when(tenantSecurity.requireControlRoom(cr.getId())).thenReturn(cr);
        when(tenantSecurity.requireCenter(c2.getId())).thenReturn(c2);
        when(tenantSecurity.requireCenter(foreign.getId())).thenReturn(foreign);

        UpdateControlRoomRequest ok = new UpdateControlRoomRequest();
        ok.setCenterId(c2.getId());
        service.update(cr.getId(), ok, actor);
        assertThat(cr.getCenter()).isSameAs(c2);

        UpdateControlRoomRequest bad = new UpdateControlRoomRequest();
        bad.setCenterId(foreign.getId());
        assertThatThrownBy(() -> service.update(cr.getId(), bad, actor)).isInstanceOf(ResourceNotFoundException.class);
        assertThat(cr.getCenter()).isSameAs(c2);
    }

    @Test
    void hospitalSupervisorMayRenameOwnRoomAndRepeatOwnCenter() {
        Center own = center(region(tA), tA);
        ControlRoom mine = room(own, tA);
        User actor = user(UserRole.hospital_supervisor, own, null, tA);
        when(tenantSecurity.requireControlRoom(mine.getId())).thenReturn(mine);
        UpdateControlRoomRequest u = new UpdateControlRoomRequest();
        u.setName("Renamed");
        u.setCenterId(own.getId());
        service.update(mine.getId(), u, actor);
        assertThat(mine.getName()).isEqualTo("Renamed");
    }

    @Test
    void systemAdminCannotMoveRoomAcrossTenants() {
        Center ca = center(region(tA), tA), cb = center(region(tB), tB);
        ControlRoom cr = room(ca, tA);
        User admin = user(UserRole.system_admin, null, null, null);
        when(tenantSecurity.requireControlRoom(cr.getId())).thenReturn(cr);
        when(tenantSecurity.requireCenter(cb.getId())).thenReturn(cb);
        UpdateControlRoomRequest u = new UpdateControlRoomRequest();
        u.setCenterId(cb.getId());
        assertThatThrownBy(() -> service.update(cr.getId(), u, admin)).isInstanceOf(IllegalArgumentException.class);
        assertThat(cr.getCenter()).isSameAs(ca);
    }
}
