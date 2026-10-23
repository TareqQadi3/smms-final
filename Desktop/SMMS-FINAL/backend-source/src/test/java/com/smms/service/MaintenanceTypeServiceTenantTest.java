package com.smms.service;

import com.smms.dto.request.CreateMaintenanceTypeRequest;
import com.smms.entity.ControlRoom;
import com.smms.entity.MaintenanceType;
import com.smms.enums.ControlRoomType;
import com.smms.exception.ResourceNotFoundException;
import com.smms.repository.ControlRoomRepository;
import com.smms.repository.MaintenanceTypeRepository;
import com.smms.util.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.smms.support.Fixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** M3-C IDOR audit: maintenance types are looked up inside the caller's tenant. */
class MaintenanceTypeServiceTenantTest {

    private final UUID tA = UUID.randomUUID();
    private final UUID tB = UUID.randomUUID();
    private MaintenanceTypeRepository repo;
    private ControlRoomRepository crRepo;
    private MaintenanceTypeService service;
    private MaintenanceType typeA;

    @BeforeEach
    void setUp() {
        repo = mock(MaintenanceTypeRepository.class);
        crRepo = mock(ControlRoomRepository.class);
        service = new MaintenanceTypeService(repo, crRepo);
        typeA = MaintenanceType.builder().name("n").nameAr("n").controlRoomType(ControlRoomType.hospital).isActive(true).build();
        typeA.setId(UUID.randomUUID());
        typeA.setTenantId(tA);
        when(repo.save(any(MaintenanceType.class))).thenAnswer(i -> i.getArgument(0));
        when(repo.findByIdAndTenantId(typeA.getId(), tA)).thenReturn(Optional.of(typeA));
        when(repo.findById(typeA.getId())).thenReturn(Optional.of(typeA));
    }

    @AfterEach
    void clear() { TenantContext.clear(); }

    private CreateMaintenanceTypeRequest req() {
        CreateMaintenanceTypeRequest r = new CreateMaintenanceTypeRequest();
        r.setName("renamed");
        r.setNameAr("renamed");
        r.setControlRoomType(ControlRoomType.hospital);
        return r;
    }

    @Test
    void tenantAdminUpdatesAndDeletesOwnType() {
        TenantContext.set(tA);
        service.update(typeA.getId(), req());
        assertThat(typeA.getName()).isEqualTo("renamed");
        service.delete(typeA.getId());
        assertThat(typeA.isActive()).isFalse();
    }

    @Test
    void tenantAdminOfAnotherTenantGets404OnUpdateAndDelete() {
        TenantContext.set(tB);
        assertThatThrownBy(() -> service.update(typeA.getId(), req())).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.delete(typeA.getId())).isInstanceOf(ResourceNotFoundException.class);
        assertThat(typeA.getName()).isEqualTo("n");
        assertThat(typeA.isActive()).isTrue();
        verify(repo, never()).save(any());
        verify(repo, never()).findById(any());
    }

    @Test
    void platformAdminMayChangeAnyTenantsType() {
        TenantContext.set(null);
        service.update(typeA.getId(), req());
        assertThat(typeA.getName()).isEqualTo("renamed");
    }

    @Test
    void listByControlRoomOfAnotherTenantIs404() {
        ControlRoom foreign = room(center(region(tA), tA), tA);
        foreign.setType(ControlRoomType.hospital);
        when(crRepo.findByIdAndTenantIdAndDeletedAtIsNull(foreign.getId(), tA)).thenReturn(Optional.of(foreign));
        when(repo.findAllByTenantIdAndControlRoomTypeAndIsActiveTrueOrderByNameAsc(tA, ControlRoomType.hospital))
            .thenReturn(List.of(typeA));

        TenantContext.set(tA);
        assertThat(service.listByControlRoom(foreign.getId())).hasSize(1);

        TenantContext.set(tB);
        assertThatThrownBy(() -> service.listByControlRoom(foreign.getId())).isInstanceOf(ResourceNotFoundException.class);
        verify(crRepo, never()).findById(any());
    }
}
