package com.smms.procurement;

import com.smms.entity.User;
import com.smms.enums.UserRole;
import com.smms.exception.ConflictException;
import com.smms.exception.ResourceNotFoundException;
import com.smms.procurement.dto.SupplierDtos.SupplierRequest;
import com.smms.procurement.dto.SupplierDtos.SupplierResponse;
import com.smms.procurement.entity.Supplier;
import com.smms.procurement.repository.SupplierRepository;
import com.smms.procurement.service.SupplierService;
import com.smms.security.AdminScopePolicy;
import com.smms.support.Fixtures;
import com.smms.util.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SupplierServiceTest {

    private final UUID tenant = UUID.randomUUID();
    private SupplierRepository repo;
    private AdminScopePolicy adminScope;
    private SupplierService service;

    @BeforeEach
    void setUp() {
        repo = mock(SupplierRepository.class);
        adminScope = mock(AdminScopePolicy.class);
        when(adminScope.resolveTenantForNewEntity(any())).thenReturn(tenant);
        when(repo.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));
        when(repo.save(any())).thenAnswer(i -> i.getArgument(0));
        service = new SupplierService(repo, adminScope);
        TenantContext.set(tenant);
    }

    @AfterEach
    void clear() { TenantContext.clear(); }

    private User user(UserRole r) { return Fixtures.user(r, null, null, tenant); }

    private static SupplierRequest req(String code) {
        return new SupplierRequest(null, code, "<b>ACME</b> Ltd", null, "123", null, null);
    }

    @Test
    void writeRolesCreateAndTheNameIsSanitised() {
        for (UserRole r : new UserRole[] {UserRole.system_admin, UserRole.maintenance_manager, UserRole.operating_company_manager}) {
            SupplierResponse s = service.create(user(r), req("C-" + r.ordinal()));
            assertThat(s.name()).isEqualTo("ACME Ltd");
            assertThat(s.active()).isTrue();
        }
        verify(adminScope, times(3)).resolveTenantForNewEntity(null);
    }

    @Test
    void otherRolesCannotWriteButReadRolesCanList() {
        for (UserRole r : new UserRole[] {UserRole.warehouse, UserRole.hospital_supervisor, UserRole.region_supervisor,
                UserRole.technician, UserRole.control_room, UserRole.resident, UserRole.other}) {
            assertThatThrownBy(() -> service.create(user(r), req("X"))).as(r.name()).isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> service.deactivate(user(r), UUID.randomUUID())).as(r.name()).isInstanceOf(AccessDeniedException.class);
        }
        for (UserRole r : new UserRole[] {UserRole.technician, UserRole.control_room, UserRole.resident}) {
            assertThatThrownBy(() -> service.list(user(r), null, null, SupplierService.pageable(0, 10, null))).as(r.name())
                .isInstanceOf(AccessDeniedException.class);
        }
    }

    @Test
    void duplicateCodeIsAConflict() {
        when(repo.existsByTenantIdAndCodeIgnoreCase(tenant, "DUP")).thenReturn(true);
        assertThatThrownBy(() -> service.create(user(UserRole.maintenance_manager), req("DUP"))).isInstanceOf(ConflictException.class);
        verify(repo, never()).saveAndFlush(any());
    }

    @Test
    void anotherTenantsSupplierIsNotFound() {
        Supplier s = new Supplier();
        s.setId(UUID.randomUUID());
        s.setTenantId(UUID.randomUUID());
        when(repo.findById(s.getId())).thenReturn(Optional.of(s));
        assertThatThrownBy(() -> service.get(user(UserRole.maintenance_manager), s.getId())).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.update(user(UserRole.maintenance_manager), s.getId(), req("C"))).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.deactivate(user(UserRole.maintenance_manager), s.getId())).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void deactivateKeepsTheRowAndCodeIsImmutable() {
        Supplier s = new Supplier();
        s.setId(UUID.randomUUID());
        s.setTenantId(tenant);
        s.setCode("KEEP");
        s.setName("n");
        when(repo.findById(s.getId())).thenReturn(Optional.of(s));
        service.update(user(UserRole.operating_company_manager), s.getId(), req("CHANGED"));
        assertThat(s.getCode()).isEqualTo("KEEP");
        assertThat(service.deactivate(user(UserRole.operating_company_manager), s.getId()).active()).isFalse();
    }
}
