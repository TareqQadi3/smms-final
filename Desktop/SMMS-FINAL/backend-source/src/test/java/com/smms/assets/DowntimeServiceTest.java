package com.smms.assets;

import com.smms.entity.*;
import com.smms.enums.AssetStatus;
import com.smms.enums.UserRole;
import com.smms.exception.ConflictException;
import com.smms.exception.WorkflowException;
import com.smms.repository.AssetRepository;
import com.smms.security.WorkOrderAccessPolicy;
import com.smms.support.Fixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DowntimeServiceTest {

    private AssetService assetService;
    private AssetRepository assetRepo;
    private AssetDowntimeRepository repo;
    private WorkOrderAccessPolicy woAccess;
    private DowntimeService service;

    private final UUID tenant = UUID.randomUUID();
    private Center center;
    private Region region;
    private Asset asset;

    @BeforeEach
    void setUp() {
        assetService = mock(AssetService.class);
        assetRepo = mock(AssetRepository.class);
        repo = mock(AssetDowntimeRepository.class);
        woAccess = mock(WorkOrderAccessPolicy.class);
        service = new DowntimeService(assetService, assetRepo, repo, woAccess);
        region = Fixtures.region(tenant);
        center = Fixtures.center(region, tenant);
        asset = Asset.builder().center(center).status(AssetStatus.active).build();
        asset.setId(UUID.randomUUID());
        asset.setTenantId(tenant);
        when(assetService.requireReadable(eq(asset.getId()), any())).thenReturn(asset);
        when(repo.saveAndFlush(any(AssetDowntime.class))).thenAnswer(i -> i.getArgument(0));
        when(repo.save(any(AssetDowntime.class))).thenAnswer(i -> i.getArgument(0));
    }

    private User user(UserRole r) { return Fixtures.user(r, center, region, tenant); }

    @Test
    void startMarksTheAssetUnderMaintenanceAndEndRestoresIt() {
        User cr = user(UserRole.control_room);
        AssetDowntime d = service.start(asset.getId(), new DowntimeService.StartRequest("pump failure", null, null), cr);
        assertThat(asset.getStatus()).isEqualTo(AssetStatus.under_maintenance);
        assertThat(d.isMarkedAsset()).isTrue();
        assertThat(d.getRecordedById()).isEqualTo(cr.getId());

        when(repo.findByIdAndAssetId(any(), eq(asset.getId()))).thenReturn(Optional.of(d));
        service.end(asset.getId(), UUID.randomUUID(), null, cr);
        assertThat(asset.getStatus()).isEqualTo(AssetStatus.active);
        assertThat(d.getEndedAt()).isNotNull();
    }

    @Test
    void endDoesNotOverrideAManualStatusChange() {
        AssetDowntime d = service.start(asset.getId(), new DowntimeService.StartRequest("x", null, null), user(UserRole.maintenance_manager));
        asset.setStatus(AssetStatus.broken);
        when(repo.findByIdAndAssetId(any(), any())).thenReturn(Optional.of(d));
        service.end(asset.getId(), UUID.randomUUID(), null, user(UserRole.maintenance_manager));
        assertThat(asset.getStatus()).isEqualTo(AssetStatus.broken);
    }

    @Test
    void overlappingPeriodIs409() {
        when(repo.existsOverlap(eq(asset.getId()), any(), any())).thenReturn(true);
        assertThatThrownBy(() -> service.start(asset.getId(), new DowntimeService.StartRequest("x", null, null), user(UserRole.control_room)))
            .isInstanceOf(ConflictException.class);
        verify(repo, never()).saveAndFlush(any());
    }

    @Test
    void futureStartAndBlankReasonAndDecommissionedAreRejected() {
        User cr = user(UserRole.control_room);
        assertThatThrownBy(() -> service.start(asset.getId(),
            new DowntimeService.StartRequest("x", null, Instant.now().plusSeconds(3600)), cr))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.start(asset.getId(), new DowntimeService.StartRequest(" ", null, null), cr))
            .isInstanceOf(IllegalArgumentException.class);
        asset.setDecommissionedAt(Instant.now());
        assertThatThrownBy(() -> service.start(asset.getId(), new DowntimeService.StartRequest("x", null, null), cr))
            .isInstanceOf(WorkflowException.class);
    }

    @Test
    void endBeforeStartOrInTheFutureOrTwiceIsRejected() {
        User mm = user(UserRole.maintenance_manager);
        AssetDowntime d = new AssetDowntime();
        d.setStartedAt(Instant.now().minusSeconds(600));
        when(repo.findByIdAndAssetId(any(), any())).thenReturn(Optional.of(d));
        assertThatThrownBy(() -> service.end(asset.getId(), UUID.randomUUID(),
            new DowntimeService.EndRequest(d.getStartedAt().minusSeconds(1)), mm)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.end(asset.getId(), UUID.randomUUID(),
            new DowntimeService.EndRequest(Instant.now().plusSeconds(60)), mm)).isInstanceOf(IllegalArgumentException.class);
        d.setEndedAt(Instant.now());
        assertThatThrownBy(() -> service.end(asset.getId(), UUID.randomUUID(), null, mm)).isInstanceOf(ConflictException.class);
    }

    @Test
    void technicianNeedsAnAssignedWorkOrderOfThisAsset() {
        User te = user(UserRole.technician);
        assertThatThrownBy(() -> service.start(asset.getId(), new DowntimeService.StartRequest("x", null, null), te))
            .isInstanceOf(IllegalArgumentException.class);

        WorkOrder wo = new WorkOrder();
        wo.setId(UUID.randomUUID());
        wo.setAsset(asset);
        when(woAccess.requireReadable(wo.getId(), te)).thenReturn(wo);
        assertThatThrownBy(() -> service.start(asset.getId(), new DowntimeService.StartRequest("x", wo.getId(), null), te))
            .isInstanceOf(AccessDeniedException.class);
        wo.setAssignedTo(te);
        assertThat(service.start(asset.getId(), new DowntimeService.StartRequest("x", wo.getId(), null), te).getWorkOrderId())
            .isEqualTo(wo.getId());

        Asset other = Asset.builder().center(center).build();
        other.setId(UUID.randomUUID());
        wo.setAsset(other);
        assertThatThrownBy(() -> service.start(asset.getId(), new DowntimeService.StartRequest("x", wo.getId(), null), te))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rolesWithoutDowntimeRightsAreForbidden() {
        for (UserRole r : new UserRole[] {UserRole.region_supervisor, UserRole.operating_company_manager,
            UserRole.warehouse, UserRole.department, UserRole.resident, UserRole.other}) {
            assertThatThrownBy(() -> service.start(asset.getId(), new DowntimeService.StartRequest("x", null, null), user(r)))
                .as(r.name()).isInstanceOf(AccessDeniedException.class);
        }
    }
}
