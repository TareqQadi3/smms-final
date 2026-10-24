package com.smms.inventory.service;

import com.smms.dto.request.CreateInventoryItemRequest;
import com.smms.dto.request.UpdateInventoryItemRequest;
import com.smms.dto.response.CenterSummary;
import com.smms.dto.response.InventoryItemResponse;
import com.smms.dto.response.InventoryItemResponse.BalanceLine;
import com.smms.dto.response.InventoryItemResponse.WarehouseView;
import com.smms.entity.Center;
import com.smms.entity.InventoryItem;
import com.smms.entity.User;
import com.smms.exception.ConflictException;
import com.smms.exception.ResourceNotFoundException;
import com.smms.inventory.entity.StockBalance;
import com.smms.inventory.entity.UnitOfMeasure;
import com.smms.inventory.repo.*;
import com.smms.repository.CenterRepository;
import com.smms.security.AdminScopePolicy;
import com.smms.util.PageRequests;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

/** Item catalog: create/update, list with stock totals, detail with per-warehouse balances, low-stock alerts. */
@Service
@RequiredArgsConstructor
@Transactional
public class ItemService {

    public static final Map<String, String> SORT = Map.of("sku", "sku", "name", "name", "category", "category", "createdAt", "createdAt");

    private final InventoryItemRepository items;
    private final StockBalanceRepository balances;
    private final InventoryMovementRepository movements;
    private final UnitOfMeasureRepository uoms;
    private final CenterRepository centers;
    private final InventoryAccess access;
    private final AdminScopePolicy adminScope;

    @Transactional(readOnly = true)
    public Page<InventoryItemResponse> list(User actor, UUID centerId, String q, String category, Boolean active,
                                            Boolean lowStock, int page, int size, List<String> sort) {
        access.requireRole(actor, InventoryAccess.CATALOG_READERS);
        Pageable pg = PageRequests.of(page, size, sort, SORT, Sort.by("name"));
        Page<InventoryItem> p = items.findAll(spec(actor, centerId, q, category, active, lowStock), pg);
        Map<UUID, BigDecimal[]> totals = totals(p.getContent());
        boolean stock = access.has(actor, InventoryAccess.STOCK_READERS);
        return p.map(i -> toResponse(i, stock ? totals.getOrDefault(i.getId(), new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO}) : null, null));
    }

    /** Low-stock alerts: active items with a reorder level at or above their total on hand. */
    @Transactional(readOnly = true)
    public Page<InventoryItemResponse> lowStock(User actor, UUID centerId, int page, int size) {
        access.requireRole(actor, InventoryAccess.STOCK_READERS);
        return list(actor, centerId, null, null, true, true, page, size, List.of("name"));
    }

    @Transactional(readOnly = true)
    public InventoryItemResponse get(User actor, UUID id) {
        access.requireRole(actor, InventoryAccess.CATALOG_READERS);
        InventoryItem i = visible(actor, id);
        if (!access.has(actor, InventoryAccess.STOCK_READERS)) return toResponse(i, null, null);
        List<StockBalance> bs = balances.findByItem(id);
        BigDecimal on = bs.stream().map(StockBalance::getOnHand).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal res = bs.stream().map(StockBalance::getReserved).reduce(BigDecimal.ZERO, BigDecimal::add);
        List<BalanceLine> lines = bs.stream().map(b -> BalanceLine.builder()
            .warehouse(new WarehouseView(b.getWarehouse().getId(), b.getWarehouse().getCode(), b.getWarehouse().getName(), b.getWarehouse().getNameAr()))
            .onHand(b.getOnHand()).reserved(b.getReserved()).available(b.available()).minQty(b.getMinQty()).build()).toList();
        return toResponse(i, new BigDecimal[] {on, res}, lines);
    }

    public InventoryItemResponse create(User actor, CreateInventoryItemRequest req) {
        access.requireRole(actor, InventoryAccess.CATALOG_WRITERS);
        Center center = centers.findById(req.getCenterId()).filter(c -> c.getDeletedAt() == null).orElse(null);
        if (center == null) throw new ResourceNotFoundException("Center not found");
        access.requireCenterInScope(actor, center);
        UUID tenant = adminScope.resolveTenantForNewEntity(null, center);
        String sku = req.getSku().trim();
        if (items.existsByCenterIdAndSkuIgnoreCase(center.getId(), sku)) throw new ConflictException("SKU already exists in this center");
        UnitOfMeasure uom = resolveUom(req.getUomId(), tenant);
        BigDecimal reorder = reorder(req.getReorderLevel(), uom);
        InventoryItem i = InventoryItem.builder().center(center).sku(sku).name(req.getName().trim()).nameAr(req.getNameAr().trim())
            .category(blankToNull(req.getCategory())).uom(uom).reorderLevel(reorder).build();
        i.setTenantId(tenant);
        return toResponse(items.save(i), new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO}, null);
    }

    public InventoryItemResponse update(User actor, UUID id, UpdateInventoryItemRequest req) {
        access.requireRole(actor, InventoryAccess.CATALOG_WRITERS);
        InventoryItem i = visible(actor, id);
        UnitOfMeasure uom = resolveUom(req.getUomId(), i.getTenantId());
        UUID oldUom = i.getUom() == null ? null : i.getUom().getId();
        UUID newUom = uom == null ? null : uom.getId();
        // the unit sets the quantity precision: it is frozen once the item has any ledger history
        if (!Objects.equals(oldUom, newUom) && movements.existsByItemId(id)) {
            throw new ConflictException("Unit of measure cannot change once stock movements exist");
        }
        i.setName(req.getName().trim()); i.setNameAr(req.getNameAr().trim());
        i.setCategory(blankToNull(req.getCategory())); i.setUom(uom);
        i.setReorderLevel(reorder(req.getReorderLevel(), uom));
        if (req.getActive() != null) i.setActive(req.getActive());
        items.save(i);
        return get(actor, id);
    }

    // ── helpers ──────────────────────────────────────────────

    InventoryItem visible(User actor, UUID id) {
        InventoryItem i = items.findById(id).filter(x -> x.getDeletedAt() == null).orElse(null);
        if (i == null || !access.tenantVisible(i) || !access.centerInScope(actor, i.getCenter())) {
            throw new ResourceNotFoundException("Item not found");
        }
        return i;
    }

    private UnitOfMeasure resolveUom(UUID uomId, UUID tenant) {
        if (uomId == null) return null;
        UnitOfMeasure u = uoms.findById(uomId).filter(x -> x.getDeletedAt() == null && Objects.equals(x.getTenantId(), tenant))
            .orElseThrow(() -> new ResourceNotFoundException("Unit of measure not found"));
        if (!u.isActive()) throw new IllegalArgumentException("Unit of measure is inactive");
        return u;
    }

    private static BigDecimal reorder(BigDecimal v, UnitOfMeasure uom) {
        if (v == null) return BigDecimal.ZERO.setScale(2);
        int scale = Math.max(v.stripTrailingZeros().scale(), 0);
        if (scale > 2 || (uom != null && scale > uom.getDecimals())) {
            throw new IllegalArgumentException("reorderLevel has too many decimal places");
        }
        return v.setScale(2);
    }

    private static String blankToNull(String s) { return s == null || s.isBlank() ? null : s.trim(); }

    private Map<UUID, BigDecimal[]> totals(Collection<InventoryItem> page) {
        if (page.isEmpty()) return Map.of();
        Map<UUID, BigDecimal[]> m = new HashMap<>();
        for (Object[] row : balances.totals(page.stream().map(InventoryItem::getId).collect(Collectors.toSet()))) {
            m.put((UUID) row[0], new BigDecimal[] {(BigDecimal) row[1], (BigDecimal) row[2]});
        }
        return m;
    }

    static InventoryItemResponse toResponse(InventoryItem i, BigDecimal[] totals, List<BalanceLine> lines) {
        Center c = i.getCenter();
        var b = InventoryItemResponse.builder().id(i.getId()).sku(i.getSku()).name(i.getName()).nameAr(i.getNameAr())
            .category(i.getCategory()).uom(i.getUom() != null ? i.getUom().getCode() : i.getUnit())
            .uomId(i.getUom() == null ? null : i.getUom().getId()).reorderLevel(i.getReorderLevel()).active(i.isActive())
            .center(CenterSummary.builder().id(c.getId()).name(c.getName()).nameAr(c.getNameAr()).code(c.getCode()).build())
            .balances(lines);
        if (totals != null) {
            b.totalOnHand(totals[0]).totalReserved(totals[1]).totalAvailable(totals[0].subtract(totals[1]))
             .lowStock(i.getReorderLevel().signum() > 0 && totals[0].compareTo(i.getReorderLevel()) <= 0);
        }
        return b.build();
    }

    private Specification<InventoryItem> spec(User actor, UUID centerId, String q, String category, Boolean active, Boolean lowStock) {
        return (r, query, cb) -> {
            var center = r.join("center");
            List<Predicate> p = new ArrayList<>();
            p.add(cb.isNull(r.get("deletedAt")));
            p.add(access.scopePredicate(actor, cb, center, r.get("tenantId")));
            if (centerId != null) p.add(cb.equal(center.get("id"), centerId));
            if (active != null) p.add(cb.equal(r.get("active"), active));
            if (category != null && !category.isBlank()) p.add(cb.equal(cb.lower(r.get("category")), category.trim().toLowerCase(Locale.ROOT)));
            if (q != null && !q.isBlank()) {
                String like = "%" + q.trim().toLowerCase(Locale.ROOT).replace("%", "").replace("_", "") + "%";
                p.add(cb.or(cb.like(cb.lower(r.get("sku")), like), cb.like(cb.lower(r.get("name")), like), cb.like(cb.lower(r.get("nameAr")), like)));
            }
            if (lowStock != null) {
                Subquery<BigDecimal> sub = query.subquery(BigDecimal.class);
                Root<StockBalance> b = sub.from(StockBalance.class);
                sub.select(cb.coalesce(cb.sum(b.<BigDecimal>get("onHand")), BigDecimal.ZERO)).where(cb.equal(b.get("item"), r));
                Predicate low = cb.and(cb.greaterThan(r.<BigDecimal>get("reorderLevel"), BigDecimal.ZERO),
                    cb.greaterThanOrEqualTo(r.<BigDecimal>get("reorderLevel"), sub));
                p.add(lowStock ? low : cb.not(low));
            }
            return cb.and(p.toArray(new Predicate[0]));
        };
    }
}
