package com.smms.procurement.service;

import com.smms.entity.User;
import com.smms.enums.UserRole;
import com.smms.exception.ConflictException;
import com.smms.exception.ResourceNotFoundException;
import com.smms.procurement.CenterScope;
import com.smms.procurement.dto.SupplierDtos.SupplierRequest;
import com.smms.procurement.dto.SupplierDtos.SupplierResponse;
import com.smms.procurement.entity.Supplier;
import com.smms.procurement.repository.SupplierRepository;
import com.smms.security.AdminScopePolicy;
import com.smms.util.InputSanitizer;
import com.smms.util.PageRequests;
import com.smms.util.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Suppliers: tenant-level master data. Read: SA RS HS MM OCM WH; write: SA MM OCM. */
@Slf4j
@Service
@RequiredArgsConstructor
public class SupplierService {

    public static final UserRole[] READ = {UserRole.system_admin, UserRole.region_supervisor,
        UserRole.hospital_supervisor, UserRole.maintenance_manager, UserRole.operating_company_manager,
        UserRole.warehouse};
    public static final UserRole[] WRITE = {UserRole.system_admin, UserRole.maintenance_manager,
        UserRole.operating_company_manager};
    public static final Map<String, String> SORT = Map.of("name", "name", "code", "code", "createdAt", "createdAt");

    private final SupplierRepository repo;
    private final AdminScopePolicy adminScope;

    public static Pageable pageable(int page, int size, List<String> sort) {
        return PageRequests.of(page, size, sort, SORT, Sort.by("name"));
    }

    @Transactional(readOnly = true)
    public Page<SupplierResponse> list(User actor, String q, Boolean active, Pageable pageable) {
        CenterScope.requireRole(actor, READ);
        UUID tenant = TenantContext.get();
        Specification<Supplier> spec = (r, cq, cb) -> cb.isNull(r.get("deletedAt"));
        if (tenant != null) spec = spec.and((r, cq, cb) -> cb.equal(r.get("tenantId"), tenant));
        if (active != null) spec = spec.and((r, cq, cb) -> cb.equal(r.get("active"), active));
        if (q != null && !q.isBlank()) {
            String like = "%" + escape(q.trim().toLowerCase(Locale.ROOT)) + "%";
            spec = spec.and((r, cq, cb) -> cb.or(
                cb.like(cb.lower(r.get("name")), like, '\\'),
                cb.like(cb.lower(r.get("code")), like, '\\')));
        }
        return repo.findAll(spec, pageable).map(SupplierService::toResponse);
    }

    @Transactional(readOnly = true)
    public SupplierResponse get(User actor, UUID id) {
        CenterScope.requireRole(actor, READ);
        return toResponse(require(id));
    }

    @Transactional
    public SupplierResponse create(User actor, SupplierRequest req) {
        CenterScope.requireRole(actor, WRITE);
        UUID tenant = adminScope.resolveTenantForNewEntity(req.tenantId());
        String code = InputSanitizer.clean(req.code(), 40);
        if (code == null || code.isBlank()) throw new IllegalArgumentException("code is required");
        if (repo.existsByTenantIdAndCodeIgnoreCase(tenant, code)) {
            throw new ConflictException("Supplier code already exists");
        }
        Supplier s = new Supplier();
        s.setTenantId(tenant);
        s.setCode(code);
        apply(s, req);
        try {
            s = repo.saveAndFlush(s);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            throw new ConflictException("Supplier code already exists");
        }
        log.info("AUDIT supplier created: id={} actor={}", s.getId(), actor.getId());
        return toResponse(s);
    }

    @Transactional
    public SupplierResponse update(User actor, UUID id, SupplierRequest req) {
        CenterScope.requireRole(actor, WRITE);
        Supplier s = require(id);
        apply(s, req); // the code never changes after creation
        log.info("AUDIT supplier updated: id={} actor={}", id, actor.getId());
        return toResponse(repo.save(s));
    }

    @Transactional
    public SupplierResponse deactivate(User actor, UUID id) {
        CenterScope.requireRole(actor, WRITE);
        Supplier s = require(id);
        s.setActive(false);
        log.info("AUDIT supplier deactivated: id={} actor={}", id, actor.getId());
        return toResponse(repo.save(s));
    }

    /** Tenant-scoped load; another tenant's id is a 404. */
    public Supplier require(UUID id) {
        Supplier s = id == null ? null : repo.findById(id).orElse(null);
        if (s == null || s.isDeleted() || !CenterScope.tenantMatches(s.getTenantId())) {
            throw new ResourceNotFoundException("Supplier not found");
        }
        return s;
    }

    private static void apply(Supplier s, SupplierRequest r) {
        s.setName(InputSanitizer.clean(r.name(), 200));
        s.setNameAr(InputSanitizer.clean(r.nameAr(), 200));
        s.setPhone(InputSanitizer.clean(r.phone(), 40));
        s.setEmail(InputSanitizer.clean(r.email(), 255));
        s.setTaxNumber(InputSanitizer.clean(r.taxNumber(), 40));
        if (s.getName() == null || s.getName().isBlank()) throw new IllegalArgumentException("name is required");
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    static SupplierResponse toResponse(Supplier s) {
        return new SupplierResponse(s.getId(), s.getCode(), s.getName(), s.getNameAr(), s.getPhone(),
            s.getEmail(), s.getTaxNumber(), s.isActive(), s.getCreatedAt());
    }
}
