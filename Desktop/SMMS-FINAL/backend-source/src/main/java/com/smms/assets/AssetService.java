package com.smms.assets;

import com.smms.dto.request.CreateAssetRequest;
import com.smms.dto.response.AssetResponse;
import com.smms.dto.response.CenterSummary;
import com.smms.dto.response.DepartmentResponse;
import com.smms.entity.Asset;
import com.smms.entity.Center;
import com.smms.entity.Department;
import com.smms.entity.User;
import com.smms.enums.AssetStatus;
import com.smms.exception.ConflictException;
import com.smms.exception.ResourceNotFoundException;
import com.smms.exception.WorkflowException;
import com.smms.repository.AssetRepository;
import com.smms.repository.DepartmentRepository;
import com.smms.security.AdminScopePolicy;
import com.smms.security.TenantSecurityService;
import com.smms.util.InputSanitizer;
import com.smms.util.PageRequests;
import com.smms.util.TenantContext;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

/** Asset register: CRUD, validation, decommissioning. Every write logs one AUDIT line. */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class AssetService {

    public static final ZoneId ZONE = ZoneId.of("Asia/Riyadh");

    public static final Map<String, String> SORT_FIELDS = Map.of(
        "name", "name", "assetCode", "assetCode", "status", "status", "criticality", "criticality",
        "createdAt", "createdAt", "installDate", "installDate", "warrantyExpiry", "warrantyExpiry");

    private final AssetRepository assetRepo;
    private final DepartmentRepository departmentRepo;
    private final TenantSecurityService tenantSecurity;
    private final AdminScopePolicy adminPolicy;
    private final AssetAccessPolicy access;
    private final AssetHistoryService history;
    private final AssetDowntimeRepository downtimeRepo;
    private final com.smms.pm.MaintenancePlanRepository planRepo;

    @Transactional(readOnly = true)
    public Page<AssetResponse> list(UUID centerId, UUID departmentId, AssetStatus status, String category, String q,
                                    boolean includeDecommissioned, int page, int size, List<String> sort, User actor) {
        Pageable pageable = PageRequests.of(page, size, sort, SORT_FIELDS, Sort.by("name"));
        Specification<Asset> spec = access.readable(actor);
        spec = spec.and((root, query, cb) -> {
            List<Predicate> p = new ArrayList<>();
            if (centerId != null) p.add(cb.equal(root.get("center").get("id"), centerId));
            if (departmentId != null) p.add(cb.equal(root.get("department").get("id"), departmentId));
            if (status != null) p.add(cb.equal(root.get("status"), status));
            if (category != null && !category.isBlank()) {
                p.add(cb.equal(cb.lower(root.get("category")), category.trim().toLowerCase(Locale.ROOT)));
            }
            if (!includeDecommissioned) p.add(cb.isNull(root.get("decommissionedAt")));
            if (q != null && !q.isBlank()) {
                String like = "%" + escapeLike(q.trim().toLowerCase(Locale.ROOT)) + "%";
                p.add(cb.or(
                    cb.like(cb.lower(root.get("name")), like, '\\'),
                    cb.like(cb.lower(root.get("nameAr")), like, '\\'),
                    cb.like(cb.lower(root.get("assetCode")), like, '\\'),
                    cb.like(cb.lower(root.get("serialNumber")), like, '\\')));
            }
            return cb.and(p.toArray(new Predicate[0]));
        });
        Page<Asset> found = assetRepo.findAll(spec, pageable);
        List<UUID> ids = found.getContent().stream().map(Asset::getId).toList();
        Map<UUID, Long> open = history.openWorkOrderCounts(ids, actor);
        Set<UUID> down = ids.isEmpty() ? Set.of() : new HashSet<>(downtimeRepo.findOpenAssetIds(ids));
        return found.map(a -> toResponse(a, open.getOrDefault(a.getId(), 0L), down.contains(a.getId())));
    }

    @Transactional(readOnly = true)
    public AssetResponse get(UUID id, User actor) {
        Asset a = requireReadable(id, actor);
        long open = history.openWorkOrderCounts(List.of(id), actor).getOrDefault(id, 0L);
        return toResponse(a, open, downtimeRepo.findByAssetIdAndEndedAtIsNull(id).isPresent());
    }

    /** 404 when the asset is missing, in another tenant, or outside the caller's scope. */
    @Transactional(readOnly = true)
    public Asset requireReadable(UUID id, User actor) {
        if (id == null) throw new ResourceNotFoundException("Asset not found");
        Specification<Asset> byId = (root, q, cb) -> cb.equal(root.get("id"), id);
        return assetRepo.findOne(access.readable(actor).and(byId))
            .orElseThrow(() -> new ResourceNotFoundException("Asset not found"));
    }

    public AssetResponse create(CreateAssetRequest req, User actor) {
        access.assertWriter(actor);
        Center center = tenantSecurity.requireCenter(req.getCenterId());
        if (!access.centerWritable(actor, center.getId())) throw new ResourceNotFoundException("Center not found");
        UUID tenantId = adminPolicy.resolveTenantForNewEntity(null, center);
        Department dept = resolveDepartment(req.getDepartmentId(), center);
        validateDates(req.getInstallDate(), req.getWarrantyExpiry());
        String code = normalizeCode(req.getAssetCode());
        assertCodeFree(center.getId(), code, null);

        Asset a = Asset.builder()
            .center(center).department(dept)
            .name(InputSanitizer.clean(req.getName(), 200))
            .nameAr(InputSanitizer.clean(req.getNameAr(), 200))
            .assetCode(code)
            .category(clean(req.getCategory(), 80))
            .manufacturer(clean(req.getManufacturer(), 120))
            .model(clean(req.getModel(), 120))
            .serialNumber(clean(req.getSerialNumber(), 120))
            .location(clean(req.getLocation(), 300))
            .installDate(req.getInstallDate())
            .warrantyExpiry(req.getWarrantyExpiry())
            .criticality(req.getCriticality())
            .status(req.getStatus() != null ? req.getStatus() : AssetStatus.active)
            .build();
        a.setTenantId(tenantId);
        Asset saved = saveOrConflict(a);
        log.info("AUDIT asset created: asset={} center={} actor={}", saved.getId(), center.getId(), actor.getId());
        return toResponse(saved, 0, false);
    }

    public AssetResponse update(UUID id, UpdateAssetRequest req, User actor) {
        access.assertWriter(actor);
        Asset a = requireReadable(id, actor);
        access.assertWritable(actor, a);
        if (a.isDecommissioned()) throw new WorkflowException("A decommissioned asset cannot be edited");
        if (!Objects.equals(a.getVersion(), req.getVersion())) {
            throw new ConflictException("The asset was modified concurrently, reload and retry");
        }
        validateDates(req.getInstallDate(), req.getWarrantyExpiry());
        String code = normalizeCode(req.getAssetCode());
        if (!Objects.equals(code, a.getAssetCode())) assertCodeFree(a.getCenter().getId(), code, a.getId());
        Department dept = resolveDepartment(req.getDepartmentId(), a.getCenter());

        a.setDepartment(dept);
        a.setName(InputSanitizer.clean(req.getName(), 200));
        a.setNameAr(InputSanitizer.clean(req.getNameAr(), 200));
        a.setAssetCode(code);
        a.setCategory(clean(req.getCategory(), 80));
        a.setManufacturer(clean(req.getManufacturer(), 120));
        a.setModel(clean(req.getModel(), 120));
        a.setSerialNumber(clean(req.getSerialNumber(), 120));
        a.setLocation(clean(req.getLocation(), 300));
        a.setInstallDate(req.getInstallDate());
        a.setWarrantyExpiry(req.getWarrantyExpiry());
        a.setCriticality(req.getCriticality());
        if (req.getStatus() != null) a.setStatus(req.getStatus());
        Asset saved = saveOrConflict(a);
        log.info("AUDIT asset updated: asset={} actor={}", saved.getId(), actor.getId());
        return get(saved.getId(), actor);
    }

    /**
     * Retires the asset: refused while it has open work orders (409), closes an open downtime
     * period and pauses its preventive maintenance plans. The row is kept for history.
     */
    public AssetResponse decommission(UUID id, String reason, User actor) {
        access.assertWriter(actor);
        Asset a = requireReadable(id, actor);
        access.assertWritable(actor, a);
        if (a.isDecommissioned()) throw new WorkflowException("The asset is already decommissioned");
        if (reason == null || reason.isBlank()) throw new IllegalArgumentException("reason is required");
        long open = history.openWorkOrderCountUnscoped(a.getId(), a.getTenantId());
        if (open > 0) {
            throw new ConflictException("The asset has " + open + " open work order(s); close or cancel them first");
        }
        Instant now = Instant.now();
        downtimeRepo.findByAssetIdAndEndedAtIsNull(id).ifPresent(d -> {
            d.setEndedAt(now.isBefore(d.getStartedAt()) ? d.getStartedAt() : now);
            d.setEndedById(actor.getId());
            downtimeRepo.save(d);
        });
        int paused = planRepo.deactivateByAsset(a.getId(), a.getTenantId());
        a.setDecommissionedAt(now);
        a.setDecommissionReason(InputSanitizer.clean(reason, 1000));
        Asset saved = assetRepo.save(a);
        log.info("AUDIT asset decommissioned: asset={} actor={} plansPaused={}", id, actor.getId(), paused);
        return get(saved.getId(), actor);
    }

    // ── helpers ──────────────────────────────────────────────

    private Department resolveDepartment(UUID departmentId, Center center) {
        if (departmentId == null) return null;
        UUID tenantId = TenantContext.get();
        Optional<Department> d = departmentRepo.findById(departmentId).filter(x -> x.getDeletedAt() == null);
        Department dept = d.filter(x -> tenantId == null || tenantId.equals(x.getTenantId()))
            .filter(x -> Objects.equals(x.getTenantId(), center.getTenantId()))
            .orElseThrow(() -> new ResourceNotFoundException("Department not found"));
        if (dept.getCenter() == null || !center.getId().equals(dept.getCenter().getId())) {
            throw new IllegalArgumentException("The department belongs to another center");
        }
        return dept;
    }

    static void validateDates(LocalDate install, LocalDate warranty) {
        if (install != null && install.isAfter(LocalDate.now(ZONE))) {
            throw new IllegalArgumentException("installDate cannot be in the future");
        }
        if (install != null && warranty != null && warranty.isBefore(install)) {
            throw new IllegalArgumentException("warrantyExpiry cannot be before installDate");
        }
    }

    static String normalizeCode(String code) {
        if (code == null || code.isBlank()) return null;
        return InputSanitizer.clean(code.trim().toUpperCase(Locale.ROOT), 60);
    }

    private void assertCodeFree(UUID centerId, String code, UUID ignoreId) {
        if (code == null) return;
        assetRepo.findLiveByCenterAndCode(centerId, code)
            .filter(x -> !x.getId().equals(ignoreId))
            .ifPresent(x -> { throw new ConflictException("Asset code already used in this center"); });
    }

    private Asset saveOrConflict(Asset a) {
        try {
            return assetRepo.saveAndFlush(a);
        } catch (DataIntegrityViolationException e) {
            throw new ConflictException("Asset code already used in this center");
        }
    }

    private static String clean(String s, int max) {
        if (s == null || s.isBlank()) return null;
        return InputSanitizer.clean(s.trim(), max);
    }

    static String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    AssetResponse toResponse(Asset a, long open, boolean downtime) {
        Center c = a.getCenter();
        Department d = a.getDepartment();
        return AssetResponse.builder()
            .id(a.getId()).assetCode(a.getAssetCode()).name(a.getName()).nameAr(a.getNameAr())
            .category(a.getCategory()).manufacturer(a.getManufacturer()).model(a.getModel())
            .serialNumber(a.getSerialNumber()).location(a.getLocation()).status(a.getStatus())
            .criticality(a.getCriticality()).installDate(a.getInstallDate()).warrantyExpiry(a.getWarrantyExpiry())
            .decommissionedAt(a.getDecommissionedAt()).decommissionReason(a.getDecommissionReason())
            .center(c == null ? null : CenterSummary.builder().id(c.getId()).name(c.getName())
                .nameAr(c.getNameAr()).code(c.getCode()).build())
            .department(d == null ? null : DepartmentResponse.builder().id(d.getId()).name(d.getName())
                .nameAr(d.getNameAr()).code(d.getCode()).active(d.isActive()).build())
            .openWorkOrders(open).activeDowntime(downtime).version(a.getVersion())
            .build();
    }
}
