package com.smms.service;

import com.smms.dto.request.CreateRegionRequest;
import com.smms.dto.request.UpdateRegionRequest;
import com.smms.entity.Region;
import com.smms.entity.User;
import com.smms.enums.UserRole;
import com.smms.repository.RegionRepository;
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

class RegionServiceTenantTest {

    private final UUID tA = UUID.randomUUID();
    private final UUID tB = UUID.randomUUID();
    private RegionRepository repo;
    private TenantRepository tenantRepo;
    private TenantSecurityService tenantSecurity;
    private RegionService service;

    @BeforeEach
    void setUp() {
        repo = mock(RegionRepository.class);
        tenantRepo = mock(TenantRepository.class);
        tenantSecurity = mock(TenantSecurityService.class);
        service = new RegionService(repo, tenantSecurity, new AdminScopePolicy(tenantRepo));
        when(repo.save(any(Region.class))).thenAnswer(i -> i.getArgument(0));
    }

    @AfterEach
    void clear() { TenantContext.clear(); }

    private CreateRegionRequest req(UUID tenantId) {
        CreateRegionRequest r = new CreateRegionRequest();
        r.setName("R");
        r.setNameAr("R");
        r.setCode("r1");
        r.setTenantId(tenantId);
        return r;
    }

    private Region saved() {
        ArgumentCaptor<Region> c = ArgumentCaptor.forClass(Region.class);
        verify(repo).save(c.capture());
        return c.getValue();
    }

    @Test
    void platformAdminMustNameTheTenant() {
        User admin = user(UserRole.system_admin, null, null, null);
        assertThatThrownBy(() -> service.create(req(null), admin)).isInstanceOf(IllegalArgumentException.class);
        verify(repo, never()).save(any());
    }

    @Test
    void platformAdminRegionGetsTheRequestedExistingTenant() {
        User admin = user(UserRole.system_admin, null, null, null);
        when(tenantRepo.existsById(tB)).thenReturn(true);
        service.create(req(tB), admin);
        assertThat(saved().getTenantId()).isEqualTo(tB);
        assertThat(saved().getCode()).isEqualTo("R1");
    }

    @Test
    void unknownTenantIsRejected() {
        User admin = user(UserRole.system_admin, null, null, null);
        assertThatThrownBy(() -> service.create(req(tB), admin)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void tenantAdminRegionAlwaysGetsContextTenant() {
        TenantContext.set(tA);
        User admin = user(UserRole.system_admin, null, null, null);
        service.create(req(tB), admin);
        assertThat(saved().getTenantId()).isEqualTo(tA);
    }

    @Test
    void onlySystemAdminMayCreateOrUpdateRegions() {
        Region r = region(tA);
        User reg = user(UserRole.region_supervisor, null, r, tA);
        User hosp = user(UserRole.hospital_supervisor, center(r, tA), null, tA);
        assertThatThrownBy(() -> service.create(req(null), reg)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.create(req(null), hosp)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.update(r.getId(), new UpdateRegionRequest(), reg)).isInstanceOf(AccessDeniedException.class);
        verify(repo, never()).save(any());
    }
}
