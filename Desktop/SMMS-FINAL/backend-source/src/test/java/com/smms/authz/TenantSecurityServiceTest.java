package com.smms.authz;

import com.smms.entity.Center;
import com.smms.entity.ControlRoom;
import com.smms.entity.Region;
import com.smms.entity.User;
import com.smms.enums.UserRole;
import com.smms.exception.ResourceNotFoundException;
import com.smms.repository.CenterRepository;
import com.smms.repository.ControlRoomRepository;
import com.smms.repository.RegionRepository;
import com.smms.repository.UserRepository;
import com.smms.security.TenantSecurityService;
import com.smms.util.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static com.smms.support.Fixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * TenantSecurityService is the choke point every admin path uses to turn an id into an entity.
 * Until now it was only ever mocked. Here it runs against mocked repositories that behave like the
 * real tenant-filtered queries, so we verify which query it picks and its defence in depth.
 */
class TenantSecurityServiceTest {

    private final UUID tA = UUID.randomUUID();
    private final UUID tB = UUID.randomUUID();
    private UserRepository users;
    private RegionRepository regions;
    private CenterRepository centers;
    private ControlRoomRepository rooms;
    private TenantSecurityService svc;

    @BeforeEach
    void setUp() {
        users = mock(UserRepository.class);
        regions = mock(RegionRepository.class);
        centers = mock(CenterRepository.class);
        rooms = mock(ControlRoomRepository.class);
        svc = new TenantSecurityService(users, regions, centers, rooms);
    }

    @AfterEach
    void clear() { TenantContext.clear(); }

    @Test
    void tenantBoundCallersUseOnlyTheTenantFilteredQueries() {
        TenantContext.set(tA);
        UUID id = UUID.randomUUID();
        when(users.findByIdAndTenantIdAndDeletedAtIsNull(id, tA)).thenReturn(Optional.empty());
        when(regions.findByIdAndTenantIdAndDeletedAtIsNull(id, tA)).thenReturn(Optional.empty());
        when(centers.findByIdAndTenantIdAndDeletedAtIsNull(id, tA)).thenReturn(Optional.empty());
        when(rooms.findByIdAndTenantIdAndDeletedAtIsNull(id, tA)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> svc.requireUser(id)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> svc.requireRegion(id)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> svc.requireCenter(id)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> svc.requireControlRoom(id)).isInstanceOf(ResourceNotFoundException.class);

        // the unfiltered findById (which would find another tenant's row) is never used
        verify(users, never()).findById(any());
        verify(regions, never()).findById(any());
        verify(centers, never()).findById(any());
        verify(rooms, never()).findById(any());
    }

    @Test
    void foreignTenantRowReturnedByARepositoryIsStillRefused() {
        // Defence in depth: even if a query leaked a row of tenant B, tenant A's caller gets 403
        TenantContext.set(tA);
        Center foreign = center(region(tB), tB);
        when(centers.findByIdAndTenantIdAndDeletedAtIsNull(foreign.getId(), tA)).thenReturn(Optional.of(foreign));
        assertThatThrownBy(() -> svc.requireCenter(foreign.getId())).isInstanceOf(AccessDeniedException.class);

        Center legacy = center(region(tA), null);
        when(centers.findByIdAndTenantIdAndDeletedAtIsNull(legacy.getId(), tA)).thenReturn(Optional.of(legacy));
        assertThatThrownBy(() -> svc.requireCenter(legacy.getId()))
            .as("a row without tenant is never served to a tenant-bound caller")
            .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void tenantBoundCallerGetsItsOwnRows() {
        TenantContext.set(tA);
        Region r = region(tA);
        Center c = center(r, tA);
        ControlRoom cr = room(c, tA);
        when(regions.findByIdAndTenantIdAndDeletedAtIsNull(r.getId(), tA)).thenReturn(Optional.of(r));
        when(centers.findByIdAndTenantIdAndDeletedAtIsNull(c.getId(), tA)).thenReturn(Optional.of(c));
        when(rooms.findByIdAndTenantIdAndDeletedAtIsNull(cr.getId(), tA)).thenReturn(Optional.of(cr));
        assertThat(svc.requireRegion(r.getId())).isSameAs(r);
        assertThat(svc.requireCenter(c.getId())).isSameAs(c);
        assertThat(svc.requireControlRoom(cr.getId())).isSameAs(cr);
    }

    @Test
    void platformAdminSeesAnyTenantButNotSoftDeletedRows() {
        // no tenant in context
        Center cb = center(region(tB), tB);
        when(centers.findById(cb.getId())).thenReturn(Optional.of(cb));
        assertThat(svc.requireCenter(cb.getId())).isSameAs(cb);

        Center gone = center(region(tB), tB);
        gone.setDeletedAt(Instant.now());
        when(centers.findById(gone.getId())).thenReturn(Optional.of(gone));
        assertThatThrownBy(() -> svc.requireCenter(gone.getId())).isInstanceOf(ResourceNotFoundException.class);

        ControlRoom goneRoom = room(cb, tB);
        goneRoom.setDeletedAt(Instant.now());
        when(rooms.findById(goneRoom.getId())).thenReturn(Optional.of(goneRoom));
        assertThatThrownBy(() -> svc.requireControlRoom(goneRoom.getId())).isInstanceOf(ResourceNotFoundException.class);

        Region rgone = region(tB);
        rgone.setDeletedAt(Instant.now());
        when(regions.findById(rgone.getId())).thenReturn(Optional.of(rgone));
        assertThatThrownBy(() -> svc.requireRegion(rgone.getId())).isInstanceOf(ResourceNotFoundException.class);

        User ugone = user(UserRole.technician, null, null, tB);
        ugone.setDeletedAt(Instant.now());
        when(users.findById(ugone.getId())).thenReturn(Optional.of(ugone));
        assertThatThrownBy(() -> svc.requireUser(ugone.getId())).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void requireUserWithRoleTreatsAWrongRoleAsNotFound() {
        TenantContext.set(tA);
        User tech = user(UserRole.technician, null, null, tA);
        when(users.findByIdAndTenantIdAndDeletedAtIsNull(tech.getId(), tA)).thenReturn(Optional.of(tech));
        assertThat(svc.requireUser(tech.getId(), UserRole.technician)).isSameAs(tech);
        assertThatThrownBy(() -> svc.requireUser(tech.getId(), UserRole.control_room))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void assertSameTenantRules() {
        svc.assertSameTenant(null);                      // platform admin: no restriction
        TenantContext.set(tA);
        svc.assertSameTenant(tA);
        assertThatThrownBy(() -> svc.assertSameTenant(tB)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> svc.assertSameTenant(null)).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void technicianListFilterIsForcedToSelf() {
        User tech = user(UserRole.technician, null, null, tA);
        UUID someoneElse = UUID.randomUUID();
        assertThat(svc.resolveAssignedToFilter(tech, someoneElse)).isEqualTo(tech.getId());
        assertThat(svc.resolveAssignedToFilter(tech, null)).isEqualTo(tech.getId());
        User cro = user(UserRole.control_room, null, null, tA);
        assertThat(svc.resolveAssignedToFilter(cro, someoneElse)).isEqualTo(someoneElse);
        assertThat(svc.resolveAssignedToFilter(null, null)).isNull();
    }
}
