package com.smms.procurement.service;

import com.smms.exception.WorkflowException;
import com.smms.inventory.api.MovementLinkProvider;
import com.smms.inventory.api.MovementReversalGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Procurement side of the inventory extension points: fills {@code poId} in movement responses (a
 * reversal is linked through {@code reversal_of_id}) and blocks reversing a receipt movement of a
 * closed purchase order (422).
 */
@Component
@RequiredArgsConstructor
public class PoReceiptMovementLinks implements MovementLinkProvider, MovementReversalGuard {

    private final NamedParameterJdbcTemplate jdbc;

    @Override
    public Map<UUID, Links> resolve(Collection<UUID> movementIds) {
        Map<UUID, Links> out = new HashMap<>();
        if (movementIds == null || movementIds.isEmpty()) return out;
        jdbc.query("""
            SELECT m.id AS movement_id, rc.po_id AS po_id
              FROM inventory_movements m
              JOIN inventory_movements o ON o.id = COALESCE(m.reversal_of_id, m.id)
              JOIN po_receipt_lines l ON l.id = o.po_receipt_line_id
              JOIN po_receipts rc ON rc.id = l.receipt_id
             WHERE m.id IN (:ids)
            """, new MapSqlParameterSource("ids", List.copyOf(movementIds)),
            rs -> { out.put(rs.getObject("movement_id", UUID.class), new Links(null, rs.getObject("po_id", UUID.class))); });
        return out;
    }

    @Override
    public void assertReversible(UUID movementId, UUID materialRequestLineId, UUID poReceiptLineId) {
        if (poReceiptLineId == null) return;
        List<String> status = jdbc.queryForList("""
            SELECT p.status FROM po_receipt_lines l
              JOIN po_receipts rc ON rc.id = l.receipt_id
              JOIN purchase_orders p ON p.id = rc.po_id
             WHERE l.id = :id
            """, new MapSqlParameterSource("id", poReceiptLineId), String.class);
        if (!status.isEmpty() && "closed".equals(status.get(0))) {
            throw new WorkflowException("The movement belongs to a closed purchase order and cannot be reversed");
        }
    }
}
