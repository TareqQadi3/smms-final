package com.smms.inventory.controller;

import com.smms.dto.request.CreateInventoryItemRequest;
import com.smms.dto.request.UpdateInventoryItemRequest;
import com.smms.dto.response.InventoryItemResponse;
import com.smms.entity.User;
import com.smms.idempotency.IdempotencyKeyValidator;
import com.smms.idempotency.IdempotencyService.Outcome;
import com.smms.inventory.api.StockLedger.MovementKind;
import com.smms.inventory.dto.Dtos.*;
import com.smms.inventory.service.*;
import com.smms.util.CurrentUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Inventory API. Role gate here (coarse); tenant/center/region scope and ownership in the services (404 when out of scope). */
@RestController
@RequestMapping("/api/inventory")
@RequiredArgsConstructor
public class InventoryController {

    private static final String READERS = "hasAnyRole('system_admin','region_supervisor','hospital_supervisor','maintenance_manager','electrical_manager','operating_company_manager','warehouse')";
    private static final String CATALOG_READERS = "hasAnyRole('system_admin','region_supervisor','hospital_supervisor','maintenance_manager','electrical_manager','operating_company_manager','warehouse','control_room','technician')";
    private static final String MOVEMENT_READERS = "hasAnyRole('system_admin','region_supervisor','hospital_supervisor','maintenance_manager','electrical_manager','operating_company_manager','warehouse','technician')";
    private static final String CATALOG_WRITERS = "hasAnyRole('system_admin','maintenance_manager','warehouse')";
    private static final String WAREHOUSE_WRITERS = "hasAnyRole('system_admin','maintenance_manager','hospital_supervisor')";
    private static final String UOM_WRITERS = "hasAnyRole('system_admin','maintenance_manager')";
    private static final String OPERATORS = "hasAnyRole('system_admin','maintenance_manager','warehouse')";
    private static final String CORRECTORS = "hasAnyRole('system_admin','maintenance_manager')";

    private final CatalogAdminService catalog;
    private final ItemService itemService;
    private final StockQueryService queries;
    private final StockOperationsService operations;

    // ── units of measure ─────────────────────────────────────

    @GetMapping("/uoms") @PreAuthorize(READERS)
    public Page<UomResponse> uoms(@CurrentUser User actor, @RequestParam(required = false) Boolean active,
                                  @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size,
                                  @RequestParam(required = false) List<String> sort) {
        return catalog.listUoms(actor, active, page, size, sort);
    }

    @PostMapping("/uoms") @PreAuthorize(UOM_WRITERS)
    public ResponseEntity<UomResponse> createUom(@CurrentUser User actor, @Valid @RequestBody UomRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(catalog.createUom(actor, req));
    }

    @PutMapping("/uoms/{id}") @PreAuthorize(UOM_WRITERS)
    public UomResponse updateUom(@CurrentUser User actor, @PathVariable UUID id, @Valid @RequestBody UomRequest req) {
        return catalog.updateUom(actor, id, req);
    }

    // ── warehouses ───────────────────────────────────────────

    @GetMapping("/warehouses") @PreAuthorize(READERS)
    public Page<WarehouseResponse> warehouses(@CurrentUser User actor, @RequestParam(required = false) UUID centerId,
                                              @RequestParam(required = false) Boolean active, @RequestParam(required = false) String q,
                                              @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size,
                                              @RequestParam(required = false) List<String> sort) {
        return catalog.listWarehouses(actor, centerId, active, q, page, size, sort);
    }

    @GetMapping("/warehouses/{id}") @PreAuthorize(READERS)
    public WarehouseResponse warehouse(@CurrentUser User actor, @PathVariable UUID id) {
        return catalog.getWarehouse(actor, id);
    }

    @PostMapping("/warehouses") @PreAuthorize(WAREHOUSE_WRITERS)
    public ResponseEntity<WarehouseResponse> createWarehouse(@CurrentUser User actor, @Valid @RequestBody WarehouseRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(catalog.createWarehouse(actor, req));
    }

    @PutMapping("/warehouses/{id}") @PreAuthorize(WAREHOUSE_WRITERS)
    public WarehouseResponse updateWarehouse(@CurrentUser User actor, @PathVariable UUID id, @Valid @RequestBody WarehouseRequest req) {
        return catalog.updateWarehouse(actor, id, req);
    }

    // ── items ────────────────────────────────────────────────

    @GetMapping("/items") @PreAuthorize(CATALOG_READERS)
    public Page<InventoryItemResponse> items(@CurrentUser User actor, @RequestParam(required = false) UUID centerId,
                                             @RequestParam(required = false) String q, @RequestParam(required = false) String category,
                                             @RequestParam(required = false) Boolean active, @RequestParam(required = false) Boolean lowStock,
                                             @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size,
                                             @RequestParam(required = false) List<String> sort) {
        return itemService.list(actor, centerId, q, category, active, lowStock, page, size, sort);
    }

    @GetMapping("/items/{id}") @PreAuthorize(CATALOG_READERS)
    public InventoryItemResponse item(@CurrentUser User actor, @PathVariable UUID id) {
        return itemService.get(actor, id);
    }

    @PostMapping("/items") @PreAuthorize(CATALOG_WRITERS)
    public ResponseEntity<InventoryItemResponse> createItem(@CurrentUser User actor, @Valid @RequestBody CreateInventoryItemRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(itemService.create(actor, req));
    }

    @PutMapping("/items/{id}") @PreAuthorize(CATALOG_WRITERS)
    public InventoryItemResponse updateItem(@CurrentUser User actor, @PathVariable UUID id, @Valid @RequestBody UpdateInventoryItemRequest req) {
        return itemService.update(actor, id, req);
    }

    @GetMapping("/items/{id}/stock-card") @PreAuthorize(READERS)
    public StockCardResponse stockCard(@CurrentUser User actor, @PathVariable UUID id, @RequestParam UUID warehouseId,
                                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                       @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        return queries.stockCard(actor, id, warehouseId, from, to, page, size);
    }

    // ── balances, alerts, movements ──────────────────────────

    @GetMapping("/balances") @PreAuthorize(READERS)
    public Page<BalanceResponse> balances(@CurrentUser User actor, @RequestParam(required = false) UUID warehouseId,
                                          @RequestParam(required = false) UUID itemId, @RequestParam(required = false) Boolean belowMin,
                                          @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size,
                                          @RequestParam(required = false) List<String> sort) {
        return queries.balances(actor, warehouseId, itemId, belowMin, page, size, sort);
    }

    @PutMapping("/balances/min-qty") @PreAuthorize(CATALOG_WRITERS)
    public BalanceResponse setMinQty(@CurrentUser User actor, @Valid @RequestBody MinQtyRequest req) {
        return queries.setMinQty(actor, req);
    }

    @GetMapping("/alerts/low-stock") @PreAuthorize(READERS)
    public Page<InventoryItemResponse> lowStock(@CurrentUser User actor, @RequestParam(required = false) UUID centerId,
                                                @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return itemService.lowStock(actor, centerId, page, size);
    }

    @GetMapping("/movements") @PreAuthorize(MOVEMENT_READERS)
    public Page<MovementResponse> movements(@CurrentUser User actor, @RequestParam(required = false) UUID itemId,
                                            @RequestParam(required = false) UUID warehouseId, @RequestParam(required = false) UUID workOrderId,
                                            @RequestParam(required = false) MovementKind kind,
                                            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size,
                                            @RequestParam(required = false) List<String> sort) {
        return queries.movements(actor, itemId, warehouseId, workOrderId, kind, from, to, page, size, sort);
    }

    // ── stock-changing operations (Idempotency-Key required) ─

    @PostMapping("/receipts") @PreAuthorize(OPERATORS)
    public ResponseEntity<ReceiptResponse> receipt(@CurrentUser User actor,
            @RequestHeader(value = IdempotencyKeyValidator.HEADER, required = false) String key, @Valid @RequestBody ReceiptRequest req) {
        return respond(operations.receipt(actor, key, req));
    }

    @PostMapping("/adjustments") @PreAuthorize(CORRECTORS)
    public ResponseEntity<MovementResponse> adjust(@CurrentUser User actor,
            @RequestHeader(value = IdempotencyKeyValidator.HEADER, required = false) String key, @Valid @RequestBody AdjustmentRequest req) {
        return respond(operations.adjust(actor, key, req));
    }

    @PostMapping("/movements/{id}/reverse") @PreAuthorize(CORRECTORS)
    public ResponseEntity<MovementResponse> reverse(@CurrentUser User actor, @PathVariable UUID id,
            @RequestHeader(value = IdempotencyKeyValidator.HEADER, required = false) String key, @Valid @RequestBody ReverseRequest req) {
        return respond(operations.reverse(actor, key, id, req));
    }

    /** 201 for the first execution, 200 + Idempotent-Replayed for a replay. */
    private static <T> ResponseEntity<T> respond(Outcome<T> o) {
        ResponseEntity.BodyBuilder b = ResponseEntity.status(o.replayed() ? HttpStatus.OK : HttpStatus.CREATED);
        if (o.replayed()) b.header("Idempotent-Replayed", "true");
        return b.body(o.body());
    }
}
