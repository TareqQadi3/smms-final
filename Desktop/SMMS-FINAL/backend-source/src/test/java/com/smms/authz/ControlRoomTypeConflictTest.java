package com.smms.authz;

import com.smms.dto.request.CreateControlRoomRequest;
import com.smms.dto.request.UpdateControlRoomRequest;
import com.smms.entity.Center;
import com.smms.entity.ControlRoom;
import com.smms.entity.Region;
import com.smms.entity.User;
import com.smms.enums.ControlRoomType;
import com.smms.enums.UserRole;
import com.smms.exception.ConflictException;
import com.smms.exception.GlobalExceptionHandler;
import com.smms.exception.ResourceNotFoundException;
import com.smms.repository.ControlRoomRepository;
import com.smms.repository.TenantRepository;
import com.smms.security.AdminScopePolicy;
import com.smms.security.TenantSecurityService;
import com.smms.service.ControlRoomService;
import com.smms.util.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.Optional;
import java.util.UUID;

import static com.smms.support.Fixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Admin check #102: a second control room of the same type in a center used to surface as a bare
 * 500 from the UNIQUE(center_id, type) index. The service now answers a clear 409 first.
 */
class ControlRoomTypeConflictTest {

    private final UUID tA = UUID.randomUUID();
    private ControlRoomRepository repo;
    private TenantSecurityService tenantSecurity;
    private ControlRoomService service;

    @BeforeEach
    void setUp() {
        repo = mock(ControlRoomRepository.class);
        tenantSecurity = mock(TenantSecurityService.class);
        service = new ControlRoomService(repo, tenantSecurity, new AdminScopePolicy(mock(TenantRepository.class)));
        when(repo.save(any(ControlRoom.class))).thenAnswer(i -> i.getArgument(0));
        when(repo.findByCenterIdAndType(any(), any())).thenReturn(Optional.empty());
        TenantContext.set(tA);
    }

    @AfterEach
    void clear() { TenantContext.clear(); }

    private CreateControlRoomRequest create(UUID centerId, ControlRoomType type) {
        CreateControlRoomRequest r = new CreateControlRoomRequest();
        r.setName("CR");
        r.setNameAr("CR");
        r.setCode("x");
        r.setType(type);
        r.setCenterId(centerId);
        return r;
    }

    @Test
    void createDuplicateTypeInCenterIsConflictAndSavesNothing() {
        Center own = center(region(tA), tA);
        User hs = user(UserRole.hospital_supervisor, own, null, tA);
        when(tenantSecurity.requireCenter(own.getId())).thenReturn(own);
        when(repo.findByCenterIdAndType(own.getId(), ControlRoomType.it)).thenReturn(Optional.of(room(own, tA)));

        assertThatThrownBy(() -> service.create(create(own.getId(), ControlRoomType.it), hs))
            .isInstanceOf(ConflictException.class)
            .hasMessageContaining("already exists");
        verify(repo, never()).save(any());
    }

    @Test
    void createOtherTypeInSameCenterIsFine() {
        Center own = center(region(tA), tA);
        User hs = user(UserRole.hospital_supervisor, own, null, tA);
        when(tenantSecurity.requireCenter(own.getId())).thenReturn(own);
        when(repo.findByCenterIdAndType(own.getId(), ControlRoomType.it)).thenReturn(Optional.of(room(own, tA)));

        service.create(create(own.getId(), ControlRoomType.medical), hs);
        verify(repo).save(any(ControlRoom.class));
    }

    @Test
    void scopeIsCheckedBeforeTheConflictSoAForeignCenterRevealsNothing() {
        Region r = region(tA);
        Center own = center(r, tA), foreign = center(r, tA);
        User hs = user(UserRole.hospital_supervisor, own, null, tA);
        when(tenantSecurity.requireCenter(foreign.getId())).thenReturn(foreign);
        when(repo.findByCenterIdAndType(foreign.getId(), ControlRoomType.it)).thenReturn(Optional.of(room(foreign, tA)));

        assertThatThrownBy(() -> service.create(create(foreign.getId(), ControlRoomType.it), hs))
            .isInstanceOf(ResourceNotFoundException.class);
        verify(repo, never()).findByCenterIdAndType(any(), any());
    }

    @Test
    void changingTypeToOneAlreadyInTheCenterIsConflict() {
        Center own = center(region(tA), tA);
        ControlRoom mine = room(own, tA);
        mine.setType(ControlRoomType.hospital);
        ControlRoom other = room(own, tA);
        User hs = user(UserRole.hospital_supervisor, own, null, tA);
        when(tenantSecurity.requireControlRoom(mine.getId())).thenReturn(mine);
        when(repo.findByCenterIdAndType(own.getId(), ControlRoomType.it)).thenReturn(Optional.of(other));

        UpdateControlRoomRequest u = new UpdateControlRoomRequest();
        u.setType(ControlRoomType.it);
        assertThatThrownBy(() -> service.update(mine.getId(), u, hs)).isInstanceOf(ConflictException.class);
        assertThat(mine.getType()).isEqualTo(ControlRoomType.hospital);
        verify(repo, never()).save(any());
    }

    @Test
    void resendingTheRoomsOwnTypeIsNotAConflict() {
        Center own = center(region(tA), tA);
        ControlRoom mine = room(own, tA);
        mine.setType(ControlRoomType.it);
        User hs = user(UserRole.hospital_supervisor, own, null, tA);
        when(tenantSecurity.requireControlRoom(mine.getId())).thenReturn(mine);
        when(repo.findByCenterIdAndType(own.getId(), ControlRoomType.it)).thenReturn(Optional.of(mine));

        UpdateControlRoomRequest u = new UpdateControlRoomRequest();
        u.setType(ControlRoomType.it);
        u.setName("Renamed");
        service.update(mine.getId(), u, hs);
        assertThat(mine.getName()).isEqualTo("Renamed");
    }

    @Test
    void movingARoomIntoACenterThatAlreadyHasThatTypeIsConflict() {
        Region r = region(tA);
        Center c1 = center(r, tA), c2 = center(r, tA);
        ControlRoom mine = room(c1, tA);
        mine.setType(ControlRoomType.it);
        User rs = user(UserRole.region_supervisor, null, r, tA);
        when(tenantSecurity.requireControlRoom(mine.getId())).thenReturn(mine);
        when(tenantSecurity.requireCenter(c2.getId())).thenReturn(c2);
        when(repo.findByCenterIdAndType(c2.getId(), ControlRoomType.it)).thenReturn(Optional.of(room(c2, tA)));

        UpdateControlRoomRequest u = new UpdateControlRoomRequest();
        u.setCenterId(c2.getId());
        assertThatThrownBy(() -> service.update(mine.getId(), u, rs)).isInstanceOf(ConflictException.class);
        assertThat(mine.getCenter()).isSameAs(c1);
    }

    @Test
    void movingARoomAndChangingItsTypeChecksTheNewPair() {
        Region r = region(tA);
        Center c1 = center(r, tA), c2 = center(r, tA);
        ControlRoom mine = room(c1, tA);
        mine.setType(ControlRoomType.it);
        User rs = user(UserRole.region_supervisor, null, r, tA);
        when(tenantSecurity.requireControlRoom(mine.getId())).thenReturn(mine);
        when(tenantSecurity.requireCenter(c2.getId())).thenReturn(c2);
        // c2 holds a medical room but no it room: moving as medical conflicts, moving as hospital is fine
        when(repo.findByCenterIdAndType(c2.getId(), ControlRoomType.medical)).thenReturn(Optional.of(room(c2, tA)));

        UpdateControlRoomRequest bad = new UpdateControlRoomRequest();
        bad.setCenterId(c2.getId());
        bad.setType(ControlRoomType.medical);
        assertThatThrownBy(() -> service.update(mine.getId(), bad, rs)).isInstanceOf(ConflictException.class);

        UpdateControlRoomRequest ok = new UpdateControlRoomRequest();
        ok.setCenterId(c2.getId());
        ok.setType(ControlRoomType.hospital);
        service.update(mine.getId(), ok, rs);
        assertThat(mine.getCenter()).isSameAs(c2);
        assertThat(mine.getType()).isEqualTo(ControlRoomType.hospital);
    }

    @Test
    void handlerMapsConflictTo409WithTheServiceMessage() {
        var resp = new GlobalExceptionHandler().conflict(new ConflictException("A control room of this type already exists in this center"));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(resp.getBody().status()).isEqualTo(409);
        assertThat(resp.getBody().message()).contains("already exists in this center");
    }
}
