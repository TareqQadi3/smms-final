package com.smms.assets;

import com.smms.dto.request.CreateAssetRequest;
import com.smms.entity.*;
import com.smms.enums.AssetStatus;
import com.smms.enums.UserRole;
import com.smms.exception.ConflictException;
import com.smms.exception.ResourceNotFoundException;
import com.smms.exception.WorkflowException;
import com.smms.pm.MaintenancePlanRepository;
import com.smms.repository.AssetRepository;
import com.smms.repository.DepartmentRepository;
import com.smms.security.AdminScopePolicy;
import com.smms.security.TenantSecurityService;
import com.smms.support.Fixtures;
import com.smms.util.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.AccessDeniedException;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AssetServiceTest {

    private AssetRepository assetRepo;
    private TenantSecurityService tenantSecurity;
    private AssetHistoryService history;
    private AssetDowntimeRepository downtimeRepo;
    private MaintenancePlanRepository planRepo;
    private AssetService service;

    private final UUID tenant = UUID.randomUUID();
    private Region region;
    private Center center;
    private Center otherCenter;
    private User mm;

    @BeforeEach
    void setUp() {
        assetRepo = mock(AssetRepository.class);
        tenantSecurity = mock(TenantSecurityService.class);
        history = mock(AssetHistoryService.class);
        downtimeRepo = mock(AssetDowntimeRepository.class);
        planRepo = mock(MaintenancePlanRepository.class);
        AdminScopePolicy adminPolicy = new AdminScopePolicy(mock(com.smms.repository.TenantRepository.class));
        service = new AssetService(assetRepo, mock(DepartmentRepository.class), tenantSecurity, adminPolicy,
            new AssetAccessPolicy(), history, downtimeRepo, planRepo);
        TenantContext.set(tenant);
        region = Fixtures.region(tenant);
        center = Fixtures.center(region, tenant);
        otherCenter = Fixtures.center(region, tenant);
        mm = Fixtures.user(UserRole.maintenance_manager, center, region, tenant);
        when(assetRepo.saveAndFlush(any(Asset.class))).thenAnswer(i -> {
            Asset a = i.getArgument(0);
            if (a.getId() == null) a.setId(UUID.randomUUID());
            if (a.getVersion() == null) a.setVersion(0L);
            return a;
        });
        when(assetRepo.save(any(Asset.class))).thenAnswer(i -> i.getArgument(0));
        when(history.openWorkOrderCounts(any(), any())).thenReturn(java.util.Map.of());
    }

    @AfterEach
    void clear() { TenantContext.clear(); }

    private CreateAssetRequest request(UUID centerId) {
        CreateAssetRequest r = new CreateAssetRequest();
        r.setCenterId(centerId);
        r.setName("Chiller");
        r.setNameAr("مبرد");
        r.setAssetCode("ch-01");
        r.setCriticality(AssetCriticality.high);
        return r;
    }

    private Asset existing(Long version) {
        Asset a = Asset.builder().center(center).name("A").nameAr("A").status(AssetStatus.active).build();
        a.setId(UUID.randomUUID());
        a.setTenantId(tenant);
        a.setVersion(version);
        return a;
    }

    @Test
    void createNormalisesTheCodeAndStampsTenantFromTheCenter() {
        when(tenantSecurity.requireCenter(center.getId())).thenReturn(center);
        var resp = service.create(request(center.getId()), mm);
        assertThat(resp.getAssetCode()).isEqualTo("CH-01");
        assertThat(resp.getStatus()).isEqualTo(AssetStatus.active);
        assertThat(resp.getCriticality()).isEqualTo(AssetCriticality.high);
    }

    @Test
    void nonWritersAreForbidden() {
        for (UserRole r : new UserRole[] {UserRole.control_room, UserRole.technician, UserRole.region_supervisor,
            UserRole.operating_company_manager, UserRole.warehouse, UserRole.department}) {
            User u = Fixtures.user(r, center, region, tenant);
            assertThatThrownBy(() -> service.create(request(center.getId()), u)).as(r.name())
                .isInstanceOf(AccessDeniedException.class);
        }
    }

    @Test
    void anotherCenterIsNotFoundForACenterBoundWriter() {
        when(tenantSecurity.requireCenter(otherCenter.getId())).thenReturn(otherCenter);
        assertThatThrownBy(() -> service.create(request(otherCenter.getId()), mm)).isInstanceOf(ResourceNotFoundException.class);
        verify(assetRepo, never()).saveAndFlush(any());
    }

    @Test
    void duplicateCodeInTheCenterIs409AndSoIsTheDbBackstop() {
        when(tenantSecurity.requireCenter(center.getId())).thenReturn(center);
        when(assetRepo.findLiveByCenterAndCode(center.getId(), "CH-01")).thenReturn(Optional.of(existing(0L)));
        assertThatThrownBy(() -> service.create(request(center.getId()), mm)).isInstanceOf(ConflictException.class);

        when(assetRepo.findLiveByCenterAndCode(any(), any())).thenReturn(Optional.empty());
        when(assetRepo.saveAndFlush(any(Asset.class))).thenThrow(new DataIntegrityViolationException("uq_assets_center_code"));
        assertThatThrownBy(() -> service.create(request(center.getId()), mm)).isInstanceOf(ConflictException.class);
    }

    @Test
    void dateRules() {
        LocalDate today = LocalDate.now(AssetService.ZONE);
        AssetService.validateDates(today, today.plusYears(1));
        AssetService.validateDates(null, today.minusDays(5));
        assertThatThrownBy(() -> AssetService.validateDates(today.plusDays(1), null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AssetService.validateDates(today.minusDays(1), today.minusDays(2)))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void blankCodesBecomeNullAndLikeWildcardsAreEscaped() {
        assertThat(AssetService.normalizeCode("  ")).isNull();
        assertThat(AssetService.normalizeCode(null)).isNull();
        assertThat(AssetService.escapeLike("50%_a\\")).isEqualTo("50\\%\\_a\\\\");
    }

    @Test
    void updateWithAStaleVersionIs409AndDecommissionedAssetsAreReadOnly() {
        Asset a = existing(3L);
        when(assetRepo.findOne(any(Specification.class))).thenReturn(Optional.of(a));
        var req = new UpdateAssetRequest();
        req.setVersion(2L);
        req.setName("n");
        req.setNameAr("n");
        assertThatThrownBy(() -> service.update(a.getId(), req, mm)).isInstanceOf(ConflictException.class);

        a.setDecommissionedAt(Instant.now());
        req.setVersion(3L);
        assertThatThrownBy(() -> service.update(a.getId(), req, mm)).isInstanceOf(WorkflowException.class);
    }

    @Test
    void decommissionIsRefusedWhileWorkOrdersAreOpen() {
        Asset a = existing(0L);
        when(assetRepo.findOne(any(Specification.class))).thenReturn(Optional.of(a));
        when(history.openWorkOrderCountUnscoped(a.getId(), tenant)).thenReturn(2L);
        assertThatThrownBy(() -> service.decommission(a.getId(), "scrapped", mm)).isInstanceOf(ConflictException.class)
            .hasMessageContaining("2");
        verify(planRepo, never()).deactivateByAsset(any(), any());
        assertThat(a.isDecommissioned()).isFalse();
    }

    @Test
    void decommissionClosesDowntimePausesPlansAndRequiresAReason() {
        Asset a = existing(0L);
        when(assetRepo.findOne(any(Specification.class))).thenReturn(Optional.of(a));
        when(history.openWorkOrderCountUnscoped(a.getId(), tenant)).thenReturn(0L);
        AssetDowntime open = new AssetDowntime();
        open.setStartedAt(Instant.now().minusSeconds(3600));
        when(downtimeRepo.findByAssetIdAndEndedAtIsNull(a.getId())).thenReturn(Optional.of(open));
        when(planRepo.deactivateByAsset(a.getId(), tenant)).thenReturn(2);

        assertThatThrownBy(() -> service.decommission(a.getId(), " ", mm)).isInstanceOf(IllegalArgumentException.class);
        service.decommission(a.getId(), "End of life", mm);

        assertThat(a.isDecommissioned()).isTrue();
        assertThat(a.getDecommissionReason()).isEqualTo("End of life");
        assertThat(open.getEndedAt()).isNotNull();
        verify(planRepo).deactivateByAsset(a.getId(), tenant);
        assertThatThrownBy(() -> service.decommission(a.getId(), "again", mm)).isInstanceOf(WorkflowException.class);
    }

    @Test
    void invisibleAssetIs404() {
        when(assetRepo.findOne(any(Specification.class))).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.get(UUID.randomUUID(), mm)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.requireReadable(null, mm)).isInstanceOf(ResourceNotFoundException.class);
    }
}
