package com.smms.service;

import com.smms.entity.*;
import com.smms.enums.UserRole;
import com.smms.exception.ResourceNotFoundException;
import com.smms.repository.TenantRepository;
import com.smms.repository.UserControlRoomAccessRepository;
import com.smms.repository.UserRepository;
import com.smms.service.TenantAssignmentService.TenantAlreadyAssignedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class TenantAssignmentServiceTest {

    private UserRepository userRepo;
    private TenantRepository tenantRepo;
    private UserControlRoomAccessRepository crRepo;
    private TenantAssignmentService service;
    private User admin;
    private User target;
    private final UUID tenantId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        userRepo = mock(UserRepository.class);
        tenantRepo = mock(TenantRepository.class);
        crRepo = mock(UserControlRoomAccessRepository.class);
        service = new TenantAssignmentService(userRepo, tenantRepo, crRepo);

        admin = new User();
        admin.setId(UUID.randomUUID());
        admin.setRole(UserRole.system_admin);

        target = new User();
        target.setId(UUID.randomUUID());
        target.setRole(UserRole.technician);
        when(userRepo.findById(target.getId())).thenReturn(Optional.of(target));
        when(tenantRepo.findById(tenantId)).thenReturn(Optional.of(
            Tenant.builder().id(tenantId).name("t").isActive(true).build()));
        when(crRepo.findByUserId(any())).thenReturn(List.of());
    }

    private Center centerOf(UUID tenant) {
        Center c = new Center();
        c.setId(UUID.randomUUID());
        c.setTenantId(tenant);
        return c;
    }

    @Test
    void nonAdminActorIsForbiddenForAssignAndList() {
        for (UserRole role : UserRole.values()) {
            if (role == UserRole.system_admin) continue;
            User actor = new User();
            actor.setRole(role);
            assertThatThrownBy(() -> service.assign(target.getId(), tenantId, actor))
                .isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> service.listTenantless(actor, org.springframework.data.domain.Pageable.unpaged()))
                .isInstanceOf(AccessDeniedException.class);
        }
        verify(userRepo, never()).save(any());
    }

    @Test
    void unknownOrDeletedTargetIsNotFound() {
        assertThatThrownBy(() -> service.assign(UUID.randomUUID(), tenantId, admin))
            .isInstanceOf(ResourceNotFoundException.class);
        target.setDeletedAt(java.time.Instant.now());
        assertThatThrownBy(() -> service.assign(target.getId(), tenantId, admin))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void unknownTenantIsNotFound() {
        assertThatThrownBy(() -> service.assign(target.getId(), UUID.randomUUID(), admin))
            .isInstanceOf(ResourceNotFoundException.class);
        verify(userRepo, never()).save(any());
    }

    @Test
    void inactiveTenantIsRejected() {
        when(tenantRepo.findById(tenantId)).thenReturn(Optional.of(
            Tenant.builder().id(tenantId).name("t").isActive(false).build()));
        assertThatThrownBy(() -> service.assign(target.getId(), tenantId, admin))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void platformAdminTargetIsRejected() {
        target.setRole(UserRole.system_admin);
        assertThatThrownBy(() -> service.assign(target.getId(), tenantId, admin))
            .isInstanceOf(IllegalArgumentException.class);
        verify(userRepo, never()).save(any());
    }

    @Test
    void alreadyTenantedTargetIsAConflictAndIsNeverMoved() {
        UUID other = UUID.randomUUID();
        target.setTenantId(other);
        assertThatThrownBy(() -> service.assign(target.getId(), tenantId, admin))
            .isInstanceOf(TenantAlreadyAssignedException.class);
        assertThat(target.getTenantId()).isEqualTo(other);
        verify(userRepo, never()).save(any());
    }

    @Test
    void centerOfAnotherTenantIsInconsistent() {
        target.setCenter(centerOf(UUID.randomUUID()));
        assertThatThrownBy(() -> service.assign(target.getId(), tenantId, admin))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Inconsistent tenant");
        assertThat(target.getTenantId()).isNull();
    }

    @Test
    void regionOfAnotherTenantIsInconsistent() {
        Region r = new Region();
        r.setId(UUID.randomUUID());
        r.setTenantId(UUID.randomUUID());
        target.setRegion(r);
        assertThatThrownBy(() -> service.assign(target.getId(), tenantId, admin))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Inconsistent tenant");
    }

    @Test
    void controlRoomOfAnotherTenantIsInconsistent() {
        ControlRoom room = new ControlRoom();
        room.setId(UUID.randomUUID());
        room.setTenantId(UUID.randomUUID());
        when(crRepo.findByUserId(target.getId())).thenReturn(List.of(
            UserControlRoomAccess.builder().user(target).controlRoom(room).build()));
        assertThatThrownBy(() -> service.assign(target.getId(), tenantId, admin))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Inconsistent tenant");
    }

    @Test
    void anchorWithoutTenantCannotBeVerifiedAndBlocksTheAssignment() {
        target.setCenter(centerOf(null));
        assertThatThrownBy(() -> service.assign(target.getId(), tenantId, admin))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("no tenant");
        verify(userRepo, never()).save(any());
    }

    @Test
    void userWithoutAnchorsGetsExactlyTheChosenTenant() {
        var res = service.assign(target.getId(), tenantId, admin);
        assertThat(target.getTenantId()).isEqualTo(tenantId);
        assertThat(res.getTenantId()).isEqualTo(tenantId);
        verify(userRepo).save(target);
    }

    @Test
    void consistentAnchorsAreAccepted() {
        target.setCenter(centerOf(tenantId));
        service.assign(target.getId(), tenantId, admin);
        assertThat(target.getTenantId()).isEqualTo(tenantId);
    }

    @Test
    void singleTenantInstallationStillNeedsAnExplicitCall() {
        // nothing assigns automatically: without a call the user stays tenant-less
        when(tenantRepo.count()).thenReturn(1L);
        assertThat(target.getTenantId()).isNull();
        verify(userRepo, never()).save(any());
    }
}
