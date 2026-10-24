package com.smms.assets;

import com.smms.entity.User;
import com.smms.entity.WorkOrder;
import com.smms.enums.SubType;
import com.smms.enums.TicketPriority;
import com.smms.enums.TicketStatus;
import com.smms.pm.MaintenancePlanRun;
import com.smms.pm.MaintenancePlanRunRepository;
import com.smms.repository.WorkOrderRepository;
import com.smms.security.WorkOrderAccessPolicy;
import com.smms.util.PageRequests;
import com.smms.util.TenantContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Root;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

/**
 * Per-asset history: work orders (only those the caller may read, via WorkOrderAccessPolicy),
 * completion records, materials (read-only, tolerant of the inventory ledger columns that W2a adds)
 * and open-order counts for the register list.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class AssetHistoryService {

    public static final List<TicketStatus> TERMINAL =
        List.of(TicketStatus.COMPLETED, TicketStatus.CLOSED, TicketStatus.CANCELLED);
    private static final int MATERIAL_ORDER_CAP = 500;
    private static final int MATERIAL_ROW_CAP = 1000;

    private final EntityManager em;
    private final WorkOrderRepository workOrderRepo;
    private final WorkOrderAccessPolicy workOrderAccess;
    private final MaintenancePlanRunRepository runRepo;
    private final JdbcTemplate jdbc;

    /** One work order of an asset, with its completion record. */
    public record AssetWorkOrderRow(UUID id, String ticketNumber, String title, TicketStatus status,
                                    TicketPriority priority, SubType subType, boolean preventive, UUID planId,
                                    Instant createdAt, Instant completedDate, Instant closedAt,
                                    boolean slaBreached, String techReport, UUID assignedToId,
                                    String assignedToName) {}

    public record MaterialRow(UUID movementId, UUID workOrderId, UUID itemId, String sku, String itemName,
                              String itemNameAr, BigDecimal quantity, String kind, Instant createdAt) {}

    private static Specification<WorkOrder> forAsset(UUID assetId) {
        return (root, q, cb) -> cb.equal(root.get("asset").get("id"), assetId);
    }

    private static Specification<WorkOrder> open() {
        return (root, q, cb) -> cb.not(root.get("status").in(TERMINAL));
    }

    /** Open orders per asset among the work orders the actor may read. */
    public Map<UUID, Long> openWorkOrderCounts(Collection<UUID> assetIds, User actor) {
        if (assetIds.isEmpty()) return Map.of();
        Specification<WorkOrder> spec = workOrderAccess.readableBy(actor).and(open())
            .and((root, q, cb) -> root.get("asset").get("id").in(assetIds));
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Object[]> cq = cb.createQuery(Object[].class);
        Root<WorkOrder> root = cq.from(WorkOrder.class);
        cq.multiselect(root.get("asset").get("id"), cb.count(root))
            .where(spec.toPredicate(root, cq, cb))
            .groupBy(root.get("asset").get("id"));
        Map<UUID, Long> out = new HashMap<>();
        for (Object[] r : em.createQuery(cq).getResultList()) out.put((UUID) r[0], (Long) r[1]);
        return out;
    }

    /** All open orders of the asset in its tenant, whoever may read them (decommission guard). */
    public long openWorkOrderCountUnscoped(UUID assetId, UUID tenantId) {
        Specification<WorkOrder> spec = forAsset(assetId).and(open())
            .and((root, q, cb) -> cb.isNull(root.get("deletedAt")))
            .and((root, q, cb) -> tenantId == null ? cb.conjunction() : cb.equal(root.get("tenantId"), tenantId));
        return workOrderRepo.count(spec);
    }

    public Page<AssetWorkOrderRow> workOrders(UUID assetId, int page, int size, User actor) {
        Pageable pageable = PageRequests.of(page, size, null, Map.of(), Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<WorkOrder> found = workOrderRepo.findAll(workOrderAccess.readableBy(actor).and(forAsset(assetId)), pageable);
        List<UUID> ids = found.getContent().stream().map(WorkOrder::getId).toList();
        Map<UUID, UUID> planByOrder = new HashMap<>();
        if (!ids.isEmpty()) {
            for (MaintenancePlanRun r : runRepo.findByWorkOrderIdIn(ids)) planByOrder.put(r.getWorkOrderId(), r.getPlanId());
        }
        return found.map(w -> new AssetWorkOrderRow(w.getId(), w.getTicketNumber(), w.getTitle(), w.getStatus(),
            w.getPriority(), w.getSubType(), planByOrder.containsKey(w.getId()), planByOrder.get(w.getId()),
            w.getCreatedAt(), w.getCompletedDate(), w.getClosedAt(), w.isSlaBreached(), w.getTechReport(),
            w.getAssignedTo() != null ? w.getAssignedTo().getId() : null,
            w.getAssignedTo() != null ? w.getAssignedTo().getFullName() : null));
    }

    /**
     * Issue/return movements of the asset's readable work orders. Works on the legacy ledger
     * (type stock_out/stock_in) and on the extended one (movement_kind issue/return_in); any data
     * problem yields an empty list instead of an error.
     */
    public List<MaterialRow> materials(UUID assetId, Instant from, Instant to, User actor) {
        Pageable cap = PageRequest.of(0, MATERIAL_ORDER_CAP, Sort.by(Sort.Direction.DESC, "createdAt"));
        List<UUID> orderIds = workOrderRepo.findAll(workOrderAccess.readableBy(actor).and(forAsset(assetId)), cap)
            .getContent().stream().map(WorkOrder::getId).toList();
        if (orderIds.isEmpty()) return List.of();
        try {
            boolean extended = hasColumn("inventory_movements", "movement_kind");
            String kind = extended
                ? "COALESCE(m.movement_kind, CASE m.type::text WHEN 'stock_out' THEN 'issue' WHEN 'stock_in' THEN 'return_in' ELSE 'adjustment' END)"
                : "CASE m.type::text WHEN 'stock_out' THEN 'issue' WHEN 'stock_in' THEN 'return_in' ELSE 'adjustment' END";
            StringBuilder sql = new StringBuilder("SELECT m.id, m.work_order_id, m.item_id, i.sku, i.name, i.name_ar, "
                + "m.quantity, " + kind + " AS kind, m.created_at FROM inventory_movements m "
                + "JOIN inventory_items i ON i.id = m.item_id WHERE m.work_order_id IN (:ids) AND m.deleted_at IS NULL "
                + "AND " + kind + " IN ('issue','return_in')");
            MapSqlParameterSource p = new MapSqlParameterSource("ids", orderIds);
            UUID tenant = TenantContext.get();
            if (tenant != null) { sql.append(" AND m.tenant_id = :tenant"); p.addValue("tenant", tenant); }
            if (from != null) { sql.append(" AND m.created_at >= :from"); p.addValue("from", java.sql.Timestamp.from(from)); }
            if (to != null) { sql.append(" AND m.created_at < :to"); p.addValue("to", java.sql.Timestamp.from(to)); }
            sql.append(" ORDER BY m.created_at DESC LIMIT ").append(MATERIAL_ROW_CAP);
            return new NamedParameterJdbcTemplate(jdbc).query(sql.toString(), p, (rs, n) -> new MaterialRow(
                rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class),
                rs.getString(4), rs.getString(5), rs.getString(6), rs.getBigDecimal(7), rs.getString(8),
                rs.getTimestamp(9) != null ? rs.getTimestamp(9).toInstant() : null));
        } catch (DataAccessException e) {
            log.warn("Asset materials unavailable: {}", e.getClass().getSimpleName());
            return List.of();
        }
    }

    private boolean hasColumn(String table, String column) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM information_schema.columns "
            + "WHERE table_schema = current_schema() AND table_name = ? AND column_name = ?", Integer.class, table, column);
        return n != null && n > 0;
    }
}
