package com.smms.materials.controller;

import com.smms.entity.User;
import com.smms.idempotency.IdempotencyKeyValidator;
import com.smms.idempotency.IdempotencyService.Outcome;
import com.smms.materials.dto.MaterialRequestDtos.*;
import com.smms.materials.entity.MaterialRequestStatus;
import com.smms.materials.service.MaterialRequestService;
import com.smms.procurement.dto.PurchaseOrderDtos.PurchaseOrderResponse;
import com.smms.util.CurrentUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class MaterialRequestController {

    private static final String READ = "hasAnyRole('system_admin','region_supervisor','hospital_supervisor',"
        + "'maintenance_manager','electrical_manager','operating_company_manager','warehouse','control_room','technician')";
    private static final String CREATE = "hasAnyRole('technician','control_room','maintenance_manager')";
    private static final String DECIDE = "hasAnyRole('maintenance_manager','operating_company_manager','electrical_manager')";
    private static final String WAREHOUSE = "hasRole('warehouse')";
    private static final String CONSUME = "hasRole('technician')";
    private static final String CANCEL = "hasAnyRole('technician','control_room','maintenance_manager','warehouse','system_admin')";
    private static final String CLOSE = "hasAnyRole('warehouse','maintenance_manager')";

    private final MaterialRequestService service;

    @PostMapping("/api/work-orders/{woId}/material-requests")
    @PreAuthorize(CREATE)
    public ResponseEntity<MaterialRequestResponse> create(@CurrentUser User actor, @PathVariable UUID woId,
                                                          @Valid @RequestBody CreateRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(actor, woId, req));
    }

    @GetMapping("/api/work-orders/{woId}/material-requests")
    @PreAuthorize(READ)
    public List<MaterialRequestSummary> forWorkOrder(@CurrentUser User actor, @PathVariable UUID woId) {
        return service.listForWorkOrder(actor, woId);
    }

    @GetMapping("/api/material-requests")
    @PreAuthorize(READ)
    public Page<MaterialRequestSummary> list(@CurrentUser User actor,
            @RequestParam(required = false) MaterialRequestStatus status,
            @RequestParam(required = false) UUID centerId,
            @RequestParam(required = false) UUID warehouseId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) List<String> sort) {
        return service.list(actor, status, centerId, warehouseId, MaterialRequestService.pageable(page, size, sort));
    }

    @GetMapping("/api/material-requests/{id}")
    @PreAuthorize(READ)
    public MaterialRequestResponse get(@CurrentUser User actor, @PathVariable UUID id) {
        return service.get(actor, id);
    }

    @PostMapping("/api/material-requests/{id}/approve")
    @PreAuthorize(DECIDE)
    public MaterialRequestResponse approve(@CurrentUser User actor, @PathVariable UUID id,
                                           @RequestBody(required = false) @Valid ApproveRequest req) {
        return service.approve(actor, id, req);
    }

    @PostMapping("/api/material-requests/{id}/reject")
    @PreAuthorize(DECIDE)
    public MaterialRequestResponse reject(@CurrentUser User actor, @PathVariable UUID id,
                                          @Valid @RequestBody ReasonRequest req) {
        return service.reject(actor, id, req);
    }

    @PostMapping("/api/material-requests/{id}/reserve")
    @PreAuthorize(WAREHOUSE)
    public ResponseEntity<MaterialRequestResponse> reserve(@CurrentUser User actor, @PathVariable UUID id,
            @RequestHeader(value = IdempotencyKeyValidator.HEADER, required = false) String key,
            @RequestBody(required = false) @Valid VersionRequest body) {
        return reply(service.reserve(actor, id, body, key));
    }

    @PostMapping("/api/material-requests/{id}/issue")
    @PreAuthorize(WAREHOUSE)
    public ResponseEntity<MaterialRequestResponse> issue(@CurrentUser User actor, @PathVariable UUID id,
            @RequestHeader(value = IdempotencyKeyValidator.HEADER, required = false) String key,
            @Valid @RequestBody IssueRequest body) {
        return reply(service.issue(actor, id, body, key));
    }

    @PostMapping("/api/material-requests/{id}/return")
    @PreAuthorize(WAREHOUSE)
    public ResponseEntity<MaterialRequestResponse> giveBack(@CurrentUser User actor, @PathVariable UUID id,
            @RequestHeader(value = IdempotencyKeyValidator.HEADER, required = false) String key,
            @Valid @RequestBody ReturnRequest body) {
        return reply(service.giveBack(actor, id, body, key));
    }

    @PostMapping("/api/material-requests/{id}/consume")
    @PreAuthorize(CONSUME)
    public ResponseEntity<MaterialRequestResponse> consume(@CurrentUser User actor, @PathVariable UUID id,
            @RequestHeader(value = IdempotencyKeyValidator.HEADER, required = false) String key,
            @Valid @RequestBody ConsumeRequest body) {
        return reply(service.consume(actor, id, body, IdempotencyKeyValidator.optional(key)));
    }

    @PostMapping("/api/material-requests/{id}/close")
    @PreAuthorize(CLOSE)
    public MaterialRequestResponse close(@CurrentUser User actor, @PathVariable UUID id,
                                         @RequestBody(required = false) @Valid VersionRequest body) {
        return service.close(actor, id, body);
    }

    @PostMapping("/api/material-requests/{id}/cancel")
    @PreAuthorize(CANCEL)
    public MaterialRequestResponse cancel(@CurrentUser User actor, @PathVariable UUID id,
                                          @Valid @RequestBody ReasonRequest req) {
        return service.cancel(actor, id, req);
    }

    @PostMapping("/api/material-requests/{id}/purchase-order")
    @PreAuthorize(CLOSE)
    public ResponseEntity<PurchaseOrderResponse> purchaseOrder(@CurrentUser User actor, @PathVariable UUID id,
                                                              @Valid @RequestBody PurchaseOrderRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createPurchaseOrder(actor, id, req));
    }

    private static ResponseEntity<MaterialRequestResponse> reply(Outcome<MaterialRequestResponse> o) {
        ResponseEntity.BodyBuilder b = ResponseEntity.ok();
        if (o.replayed()) b.header("Idempotent-Replayed", "true");
        return b.body(o.body());
    }
}
