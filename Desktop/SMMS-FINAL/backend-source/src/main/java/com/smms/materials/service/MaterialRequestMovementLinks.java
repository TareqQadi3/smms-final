package com.smms.materials.service;

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
 * Material-request side of the inventory extension points: fills {@code materialRequestId} in movement
 * responses (a reversal is linked through {@code reversal_of_id} to the movement it reverses, because
 * reversals copy only the work order) and blocks reversing a movement of a closed request (422).
 */
@Component
@RequiredArgsConstructor
public class MaterialRequestMovementLinks implements MovementLinkProvider, MovementReversalGuard {

    private final NamedParameterJdbcTemplate jdbc;

    @Override
    public Map<UUID, Links> resolve(Collection<UUID> movementIds) {
        Map<UUID, Links> out = new HashMap<>();
        if (movementIds == null || movementIds.isEmpty()) return out;
        jdbc.query("""
            SELECT m.id AS movement_id, r.id AS mr_id
              FROM inventory_movements m
              JOIN inventory_movements o ON o.id = COALESCE(m.reversal_of_id, m.id)
              JOIN material_request_lines l ON l.id = o.material_request_line_id
              JOIN material_requests r ON r.id = l.mr_id
             WHERE m.id IN (:ids)
            """, new MapSqlParameterSource("ids", List.copyOf(movementIds)),
            rs -> { out.put(rs.getObject("movement_id", UUID.class), new Links(rs.getObject("mr_id", UUID.class), null)); });
        return out;
    }

    @Override
    public void assertReversible(UUID movementId, UUID materialRequestLineId, UUID poReceiptLineId) {
        if (materialRequestLineId == null) return;
        List<String> status = jdbc.queryForList("""
            SELECT r.status FROM material_request_lines l JOIN material_requests r ON r.id = l.mr_id WHERE l.id = :id
            """, new MapSqlParameterSource("id", materialRequestLineId), String.class);
        if (!status.isEmpty() && "closed".equals(status.get(0))) {
            throw new WorkflowException("The movement belongs to a closed material request and cannot be reversed");
        }
    }
}
