package com.smms.assets;

import com.smms.entity.Asset;
import com.smms.entity.User;
import com.smms.entity.WorkOrder;
import com.smms.enums.AssetStatus;
import com.smms.enums.UserRole;
import com.smms.exception.ConflictException;
import com.smms.exception.ResourceNotFoundException;
import com.smms.exception.WorkflowException;
import com.smms.repository.AssetRepository;
import com.smms.security.WorkOrderAccessPolicy;
import com.smms.util.InputSanitizer;
import com.smms.util.PageRequests;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Asset downtime. Roles: control_room, maintenance_manager, electrical_manager, hospital_supervisor,
 * system_admin, and a technician only against a work order assigned to them for this asset.
 * Starting downtime marks an active asset under_maintenance; ending restores active only when this
 * period made the change and the status was not changed by hand since.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class DowntimeService {

    static final Set<UserRole> ROLES = Set.of(UserRole.control_room, UserRole.maintenance_manager,
        UserRole.electrical_manager, UserRole.hospital_supervisor, UserRole.system_admin, UserRole.technician);
    private static final Instant FAR_FUTURE = Instant.parse("2999-01-01T00:00:00Z");
    private static final Map<String, String> SORT = Map.of("startedAt", "startedAt");

    private final AssetService assetService;
    private final AssetRepository assetRepo;
    private final AssetDowntimeRepository repo;
    private final WorkOrderAccessPolicy workOrderAccess;

    public record StartRequest(@jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 500) String reason,
                               UUID workOrderId, Instant startedAt) {}

    public record EndRequest(Instant endedAt) {}

    @Transactional(readOnly = true)
    public Page<AssetDowntime> list(UUID assetId, int page, int size, User actor) {
        Asset a = assetService.requireReadable(assetId, actor);
        Pageable p = PageRequests.of(page, size, null, SORT, Sort.by(Sort.Direction.DESC, "startedAt"));
        return repo.findByAssetIdAndTenantId(a.getId(), a.getTenantId(), p);
    }

    public AssetDowntime start(UUID assetId, StartRequest req, User actor) {
        assertRole(actor);
        Asset asset = assetService.requireReadable(assetId, actor);
        if (asset.isDecommissioned()) throw new WorkflowException("The asset is decommissioned");
        if (req.reason() == null || req.reason().isBlank()) throw new IllegalArgumentException("reason is required");
        Instant now = Instant.now();
        Instant start = req.startedAt() != null ? req.startedAt() : now;
        if (start.isAfter(now)) throw new IllegalArgumentException("startedAt cannot be in the future");
        UUID woId = null;
        if (req.workOrderId() != null || actor.getRole() == UserRole.technician) {
            if (req.workOrderId() == null) throw new IllegalArgumentException("workOrderId is required");
            WorkOrder wo = workOrderAccess.requireReadable(req.workOrderId(), actor);
            if (wo.getAsset() == null || !assetId.equals(wo.getAsset().getId())) {
                throw new IllegalArgumentException("The work order belongs to another asset");
            }
            if (actor.getRole() == UserRole.technician) assertAssigned(wo, actor);
            woId = wo.getId();
        }
        if (repo.existsOverlap(assetId, start, FAR_FUTURE)) {
            throw new ConflictException("The asset already has a downtime period overlapping this one");
        }
        AssetDowntime d = new AssetDowntime();
        d.setTenantId(asset.getTenantId());
        d.setAssetId(assetId);
        d.setWorkOrderId(woId);
        d.setStartedAt(start);
        d.setReason(InputSanitizer.clean(req.reason().trim(), 500));
        d.setRecordedById(actor.getId());
        if (asset.getStatus() == AssetStatus.active) {
            asset.setStatus(AssetStatus.under_maintenance);
            d.setMarkedAsset(true);
            assetRepo.save(asset);
        }
        try {
            d = repo.saveAndFlush(d);
        } catch (DataIntegrityViolationException e) {
            throw new ConflictException("The asset already has an open downtime period");
        }
        log.info("AUDIT asset downtime started: asset={} downtime={} actor={}", assetId, d.getId(), actor.getId());
        return d;
    }

    public AssetDowntime end(UUID assetId, UUID downtimeId, EndRequest req, User actor) {
        assertRole(actor);
        Asset asset = assetService.requireReadable(assetId, actor);
        AssetDowntime d = repo.findByIdAndAssetId(downtimeId, assetId)
            .orElseThrow(() -> new ResourceNotFoundException("Downtime not found"));
        if (actor.getRole() == UserRole.technician) {
            if (d.getWorkOrderId() == null) throw new AccessDeniedException("Not your downtime");
            assertAssigned(workOrderAccess.requireReadable(d.getWorkOrderId(), actor), actor);
        }
        if (d.getEndedAt() != null) throw new ConflictException("The downtime period has already ended");
        Instant now = Instant.now();
        Instant end = req != null && req.endedAt() != null ? req.endedAt() : now;
        if (end.isAfter(now)) throw new IllegalArgumentException("endedAt cannot be in the future");
        if (end.isBefore(d.getStartedAt())) throw new IllegalArgumentException("endedAt cannot be before startedAt");
        d.setEndedAt(end);
        d.setEndedById(actor.getId());
        if (d.isMarkedAsset() && asset.getStatus() == AssetStatus.under_maintenance) {
            asset.setStatus(AssetStatus.active);
            assetRepo.save(asset);
        }
        AssetDowntime saved = repo.save(d);
        log.info("AUDIT asset downtime ended: asset={} downtime={} actor={}", assetId, downtimeId, actor.getId());
        return saved;
    }

    private static void assertRole(User actor) {
        if (actor == null || actor.getRole() == null || !ROLES.contains(actor.getRole())) {
            throw new AccessDeniedException("Your role may not record asset downtime");
        }
    }

    private static void assertAssigned(WorkOrder wo, User actor) {
        if (wo.getAssignedTo() == null || !actor.getId().equals(wo.getAssignedTo().getId())) {
            throw new AccessDeniedException("The work order is not assigned to you");
        }
    }
}
