package com.smms.inventory.service;

import com.smms.entity.Center;
import com.smms.entity.User;
import com.smms.exception.ConflictException;
import com.smms.exception.ResourceNotFoundException;
import com.smms.inventory.dto.Dtos.*;
import com.smms.inventory.entity.StockBalance;
import com.smms.inventory.entity.UnitOfMeasure;
import com.smms.inventory.entity.Warehouse;
import com.smms.inventory.repo.*;
import com.smms.repository.CenterRepository;
import com.smms.security.AdminScopePolicy;
import com.smms.util.PageRequests;
import com.smms.util.TenantContext;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/** Units of measure and warehouses. */
@Service
@RequiredArgsConstructor
@Transactional
public class CatalogAdminService {

    private final UnitOfMeasureRepository uoms;
    private final WarehouseRepository warehouses;
    private final StockBalanceRepository balances;
    private final InventoryItemRepository items;
    private final InventoryMovementRepository movements;
    private final CenterRepository centers;
    private final InventoryAccess access;
    private final AdminScopePolicy adminScope;

    public static final Map<String, String> UOM_SORT = Map.of("code", "code", "name", "name", "createdAt", "createdAt");
    public static final Map<String, String> WH_SORT = Map.of("code", "code", "name", "name", "createdAt", "createdAt");

    // ── units of measure ─────────────────────────────────────

    @Transactional(readOnly = true)
    public Page<UomResponse> listUoms(User actor, Boolean active, int page, int size, List<String> sort) {
        access.requireRole(actor, InventoryAccess.STOCK_READERS);
        Pageable pg = PageRequests.of(page, size, sort, UOM_SORT, Sort.by("code"));
        UUID tenant = TenantContext.get();
        Specification<UnitOfMeasure> spec = (r, q, cb) -> {
            List<Predicate> p = new ArrayList<>();
            p.add(cb.isNull(r.get("deletedAt")));
            if (tenant != null) p.add(cb.equal(r.get("tenantId"), tenant));
            if (active != null) p.add(cb.equal(r.get("active"), active));
            return cb.and(p.toArray(new Predicate[0]));
        };
        return uoms.findAll(spec, pg).map(CatalogAdminService::toResponse);
    }

    public UomResponse createUom(User actor, UomRequest req) {
        access.requireRole(actor, InventoryAccess.UOM_WRITERS);
        UUID tenant = adminScope.resolveTenantForNewEntity(req.tenantId());
        String code = req.code().trim();
        if (uoms.existsByTenantIdAndCodeIgnoreCase(tenant, code)) throw new ConflictException("Unit code already exists");
        UnitOfMeasure u = UnitOfMeasure.builder().code(code).name(req.name().trim()).nameAr(req.nameAr().trim())
            .decimals(req.decimals().shortValue()).active(req.active() == null || req.active()).build();
        u.setTenantId(tenant);
        return toResponse(uoms.save(u));
    }

    public UomResponse updateUom(User actor, UUID id, UomRequest req) {
        access.requireRole(actor, InventoryAccess.UOM_WRITERS);
        UnitOfMeasure u = uoms.findById(id).filter(x -> x.getDeletedAt() == null && access.tenantVisible(x))
            .orElseThrow(() -> new ResourceNotFoundException("Unit of measure not found"));
        String code = req.code().trim();
        if (!u.getCode().equalsIgnoreCase(code) && uoms.existsByTenantIdAndCodeIgnoreCaseAndIdNot(u.getTenantId(), code, id)) {
            throw new ConflictException("Unit code already exists");
        }
        // lowering the precision under existing stock would make recorded quantities unrepresentable
        if (req.decimals() < u.getDecimals() && movements.existsByUom(id)) {
            throw new ConflictException("Decimals cannot be lowered while stock movements exist for this unit");
        }
        u.setCode(code); u.setName(req.name().trim()); u.setNameAr(req.nameAr().trim());
        u.setDecimals(req.decimals().shortValue());
        if (req.active() != null) u.setActive(req.active());
        return toResponse(uoms.save(u));
    }

    static UomResponse toResponse(UnitOfMeasure u) {
        return new UomResponse(u.getId(), u.getCode(), u.getName(), u.getNameAr(), u.getDecimals(), u.isActive());
    }

    // ── warehouses ───────────────────────────────────────────

    @Transactional(readOnly = true)
    public Page<WarehouseResponse> listWarehouses(User actor, UUID centerId, Boolean active, String q,
                                                  int page, int size, List<String> sort) {
        access.requireRole(actor, InventoryAccess.STOCK_READERS);
        Pageable pg = PageRequests.of(page, size, sort, WH_SORT, Sort.by("code"));
        Specification<Warehouse> spec = (r, query, cb) -> {
            var center = r.join("center");
            List<Predicate> p = new ArrayList<>();
            p.add(cb.isNull(r.get("deletedAt")));
            p.add(access.scopePredicate(actor, cb, center, r.get("tenantId")));
            if (centerId != null) p.add(cb.equal(center.get("id"), centerId));
            if (active != null) p.add(cb.equal(r.get("active"), active));
            if (q != null && !q.isBlank()) {
                String like = "%" + q.trim().toLowerCase(Locale.ROOT).replace("%", "").replace("_", "") + "%";
                p.add(cb.or(cb.like(cb.lower(r.get("code")), like), cb.like(cb.lower(r.get("name")), like),
                    cb.like(cb.lower(r.get("nameAr")), like)));
            }
            return cb.and(p.toArray(new Predicate[0]));
        };
        return warehouses.findAll(spec, pg).map(CatalogAdminService::toResponse);
    }

    @Transactional(readOnly = true)
    public WarehouseResponse getWarehouse(User actor, UUID id) {
        access.requireRole(actor, InventoryAccess.STOCK_READERS);
        return toResponse(visibleWarehouse(actor, id));
    }

    public WarehouseResponse createWarehouse(User actor, WarehouseRequest req) {
        access.requireRole(actor, InventoryAccess.WAREHOUSE_WRITERS);
        Center center = centers.findById(req.centerId()).filter(c -> c.getDeletedAt() == null).orElse(null);
        if (center == null) throw new ResourceNotFoundException("Center not found");
        access.requireCenterInScope(actor, center);
        UUID tenant = adminScope.resolveTenantForNewEntity(null, center);
        String code = req.code().trim().toUpperCase(Locale.ROOT);
        if (warehouses.existsByCenterIdAndCodeIgnoreCase(center.getId(), code)) throw new ConflictException("Warehouse code already exists in this center");
        Warehouse w = Warehouse.builder().center(center).code(code).name(req.name().trim()).nameAr(req.nameAr().trim())
            .active(req.active() == null || req.active()).build();
        w.setTenantId(tenant);
        return toResponse(warehouses.save(w));
    }

    public WarehouseResponse updateWarehouse(User actor, UUID id, WarehouseRequest req) {
        access.requireRole(actor, InventoryAccess.WAREHOUSE_WRITERS);
        Warehouse w = visibleWarehouse(actor, id);
        if (!w.getCenter().getId().equals(req.centerId())) {
            throw new com.smms.exception.WorkflowException("The center of a warehouse cannot be changed");
        }
        String code = req.code().trim().toUpperCase(Locale.ROOT);
        if (!w.getCode().equalsIgnoreCase(code) && warehouses.existsByCenterIdAndCodeIgnoreCaseAndIdNot(w.getCenter().getId(), code, id)) {
            throw new ConflictException("Warehouse code already exists in this center");
        }
        boolean active = req.active() == null ? w.isActive() : req.active();
        if (w.isActive() && !active && holdsStock(id)) {
            throw new ConflictException("Warehouse still holds stock: move or adjust it to zero before deactivating");
        }
        w.setCode(code); w.setName(req.name().trim()); w.setNameAr(req.nameAr().trim()); w.setActive(active);
        return toResponse(warehouses.save(w));
    }

    private boolean holdsStock(UUID warehouseId) {
        return balances.findAll((r, q, cb) -> cb.and(cb.equal(r.get("warehouse").get("id"), warehouseId),
            cb.or(cb.greaterThan(r.get("onHand"), java.math.BigDecimal.ZERO), cb.greaterThan(r.get("reserved"), java.math.BigDecimal.ZERO))))
            .stream().findAny().isPresent();
    }

    private Warehouse visibleWarehouse(User actor, UUID id) {
        Warehouse w = warehouses.findById(id).filter(x -> x.getDeletedAt() == null).orElse(null);
        if (w == null || !access.tenantVisible(w) || !access.centerInScope(actor, w.getCenter())) {
            throw new ResourceNotFoundException("Warehouse not found");
        }
        return w;
    }

    static WarehouseResponse toResponse(Warehouse w) {
        return new WarehouseResponse(w.getId(), w.getCenter().getId(), w.getCenter().getName(), w.getCode(),
            w.getName(), w.getNameAr(), w.isActive());
    }
}
