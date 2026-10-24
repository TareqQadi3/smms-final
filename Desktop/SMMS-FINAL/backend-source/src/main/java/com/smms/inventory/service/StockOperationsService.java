package com.smms.inventory.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smms.entity.InventoryItem;
import com.smms.entity.InventoryMovement;
import com.smms.entity.User;
import com.smms.exception.ConflictException;
import com.smms.exception.ResourceNotFoundException;
import com.smms.exception.WorkflowException;
import com.smms.idempotency.IdempotencyService;
import com.smms.idempotency.IdempotencyService.Outcome;
import com.smms.idempotency.IdempotencyService.Result;
import com.smms.inventory.api.MovementReversalGuard;
import com.smms.inventory.api.StockLedger.Access;
import com.smms.inventory.api.StockLedger.MovementKind;
import com.smms.inventory.api.StockLedger.StockCommand;
import com.smms.inventory.dto.Dtos.*;
import com.smms.inventory.entity.Warehouse;
import com.smms.inventory.repo.InventoryItemRepository;
import com.smms.inventory.repo.InventoryMovementRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Stock-changing use cases (receipt, adjustment, reversal). Deliberately NOT transactional: the
 * {@link IdempotencyService} opens the transaction that holds the idempotency record, the checks and
 * the ledger rows together, so a retry with the same key replays instead of repeating.
 */
@Service
@RequiredArgsConstructor
public class StockOperationsService {

    private final IdempotencyService idempotency;
    private final ObjectMapper mapper;
    private final StockLedgerService ledger;
    private final InventoryItemRepository items;
    private final InventoryMovementRepository movements;
    private final StockQueryService queries;
    private final InventoryAccess access;
    private final ObjectProvider<MovementReversalGuard> guards;

    public Outcome<ReceiptResponse> receipt(User actor, String key, ReceiptRequest req) {
        access.requireRole(actor, InventoryAccess.OPERATORS);
        String fp = IdempotencyService.fingerprint(mapper, req);
        return idempotency.execute(actor, "inventory.receipt", key, fp, recId -> {
            Warehouse w = operableWarehouse(actor, req.warehouseId());
            Set<UUID> seen = new HashSet<>();
            List<StockCommand> cmds = new ArrayList<>();
            for (ReceiptLine l : req.lines()) {
                if (!seen.add(l.itemId())) throw new IllegalArgumentException("An item may appear only once per receipt");
                ledger.requireItem(l.itemId(), w.getCenter().getId());
                cmds.add(new StockCommand(w.getTenantId(), w.getId(), l.itemId(), MovementKind.receipt, l.quantity(), false,
                    null, null, null, null, null, blankToNull(req.note()), blankToNull(req.reference()), actor, recId));
            }
            ledger.apply(cmds);
            return new Result<>(receiptBody(recId), recId);
        }, this::receiptBody);
    }

    public Outcome<MovementResponse> adjust(User actor, String key, AdjustmentRequest req) {
        access.requireRole(actor, InventoryAccess.CORRECTORS);
        boolean in = req.direction().equals("in");
        // reason and direction must make sense together
        if (in && (req.reasonCode().equals("damage") || req.reasonCode().equals("expired"))) {
            throw new IllegalArgumentException("Reason " + req.reasonCode() + " can only reduce stock");
        }
        if (!in && req.reasonCode().equals("found")) throw new IllegalArgumentException("Reason found can only increase stock");
        String fp = IdempotencyService.fingerprint(mapper, req);
        return idempotency.execute(actor, "inventory.adjust", key, fp, recId -> {
            Warehouse w = operableWarehouse(actor, req.warehouseId());
            ledger.requireItem(req.itemId(), w.getCenter().getId());
            ledger.apply(List.of(new StockCommand(w.getTenantId(), w.getId(), req.itemId(),
                in ? MovementKind.adjustment_in : MovementKind.adjustment_out, req.quantity(), false,
                null, null, null, null, req.reasonCode(), req.note().trim(), null, actor, recId)));
            return new Result<>(single(recId), recId);
        }, this::single);
    }

    public Outcome<MovementResponse> reverse(User actor, String key, UUID movementId, ReverseRequest req) {
        access.requireRole(actor, InventoryAccess.CORRECTORS);
        String fp = IdempotencyService.fingerprint(mapper, req, movementId);
        return idempotency.execute(actor, "inventory.reverse", key, fp, recId -> {
            InventoryMovement orig = movements.findById(movementId).filter(m -> m.getDeletedAt() == null).orElse(null);
            InventoryItem item = orig == null ? null : orig.getItem();
            if (orig == null || !access.tenantVisible(orig) || !access.centerInScope(actor, item.getCenter())) {
                throw new ResourceNotFoundException("Movement not found");
            }
            if (orig.getMovementKind() == null || orig.getWarehouse() == null) {
                throw new WorkflowException("Legacy movements cannot be reversed");
            }
            if (orig.getMovementKind() == MovementKind.reversal_in || orig.getMovementKind() == MovementKind.reversal_out) {
                throw new WorkflowException("A reversal cannot be reversed");
            }
            Warehouse w = operableWarehouse(actor, orig.getWarehouse().getId());
            if (movements.existsByReversalOfId(orig.getId())) throw new ConflictException("Movement is already reversed");
            guards.orderedStream().forEach(g -> g.assertReversible(orig.getId(), orig.getMaterialRequestLineId(), orig.getPoReceiptLineId()));
            ledger.apply(List.of(new StockCommand(w.getTenantId(), w.getId(), item.getId(),
                orig.getMovementKind().isInbound() ? MovementKind.reversal_out : MovementKind.reversal_in, orig.getQuantity(), false,
                orig.getWorkOrder() == null ? null : orig.getWorkOrder().getId(), null, null, orig.getId(),
                "reversal", req.reason().trim(), null, actor, recId)));
            return new Result<>(single(recId), recId);
        }, this::single);
    }

    private Warehouse operableWarehouse(User actor, UUID warehouseId) {
        ledger.requireWarehouse(warehouseId, actor, Access.OPERATE);
        return ledger.loadWarehouse(warehouseId, actor);
    }

    private ReceiptResponse receiptBody(UUID recId) {
        return new ReceiptResponse(queries.toResponses(movements.findByIdempotencyRecord(recId)));
    }

    private MovementResponse single(UUID recId) {
        return queries.toResponses(movements.findByIdempotencyRecord(recId)).get(0);
    }

    private static String blankToNull(String s) { return s == null || s.isBlank() ? null : s.trim(); }
}
