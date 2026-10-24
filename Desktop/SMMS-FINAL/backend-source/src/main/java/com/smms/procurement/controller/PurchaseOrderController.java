package com.smms.procurement.controller;

import com.smms.entity.User;
import com.smms.idempotency.IdempotencyKeyValidator;
import com.smms.idempotency.IdempotencyService.Outcome;
import com.smms.procurement.dto.PurchaseOrderDtos.*;
import com.smms.procurement.entity.PurchaseOrderStatus;
import com.smms.procurement.service.PurchaseOrderService;
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

@RestController
@RequestMapping("/api/procurement/purchase-orders")
@RequiredArgsConstructor
public class PurchaseOrderController {

    private static final String READ = "hasAnyRole('system_admin','region_supervisor','hospital_supervisor',"
        + "'maintenance_manager','operating_company_manager','warehouse')";
    private static final String EDIT = "hasAnyRole('warehouse','maintenance_manager')";
    private static final String APPROVE = "hasAnyRole('operating_company_manager','maintenance_manager','system_admin')";
    private static final String CANCEL = "hasAnyRole('warehouse','maintenance_manager','operating_company_manager','system_admin')";
    private static final String RECEIVE = "hasRole('warehouse')";
    private static final String CLOSE = "hasAnyRole('maintenance_manager','operating_company_manager')";

    private final PurchaseOrderService service;

    @GetMapping
    @PreAuthorize(READ)
    public Page<PurchaseOrderSummary> list(@CurrentUser User actor,
            @RequestParam(required = false) PurchaseOrderStatus status,
            @RequestParam(required = false) UUID centerId,
            @RequestParam(required = false) UUID supplierId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) List<String> sort) {
        return service.list(actor, status, centerId, supplierId, from, to,
            PurchaseOrderService.pageable(page, size, sort));
    }

    @GetMapping("/{id}")
    @PreAuthorize(READ)
    public PurchaseOrderResponse get(@CurrentUser User actor, @PathVariable UUID id) {
        return service.get(actor, id);
    }

    @PostMapping
    @PreAuthorize(EDIT)
    public ResponseEntity<PurchaseOrderResponse> create(@CurrentUser User actor,
                                                        @Valid @RequestBody PurchaseOrderRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(actor, req));
    }

    @PutMapping("/{id}")
    @PreAuthorize(EDIT)
    public PurchaseOrderResponse update(@CurrentUser User actor, @PathVariable UUID id,
                                        @Valid @RequestBody PurchaseOrderRequest req) {
        return service.update(actor, id, req);
    }

    @PostMapping("/{id}/submit")
    @PreAuthorize(EDIT)
    public PurchaseOrderResponse submit(@CurrentUser User actor, @PathVariable UUID id,
                                        @RequestBody(required = false) @Valid NoteRequest body) {
        return service.submit(actor, id, body == null ? null : body.version());
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize(APPROVE)
    public PurchaseOrderResponse approve(@CurrentUser User actor, @PathVariable UUID id,
                                         @RequestBody(required = false) @Valid NoteRequest body) {
        return service.approve(actor, id, body == null ? null : body.note(), body == null ? null : body.version());
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize(APPROVE)
    public PurchaseOrderResponse reject(@CurrentUser User actor, @PathVariable UUID id,
                                        @Valid @RequestBody ReasonRequest body) {
        return service.reject(actor, id, body.reason(), body.version());
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize(CANCEL)
    public PurchaseOrderResponse cancel(@CurrentUser User actor, @PathVariable UUID id,
                                        @Valid @RequestBody ReasonRequest body) {
        return service.cancel(actor, id, body.reason(), body.version());
    }

    @PostMapping("/{id}/close")
    @PreAuthorize(CLOSE)
    public PurchaseOrderResponse close(@CurrentUser User actor, @PathVariable UUID id,
                                       @Valid @RequestBody ReasonRequest body) {
        return service.close(actor, id, body.reason(), body.version());
    }

    @PostMapping("/{id}/receive")
    @PreAuthorize(RECEIVE)
    public ResponseEntity<ReceiptResponse> receive(@CurrentUser User actor, @PathVariable UUID id,
            @RequestHeader(value = IdempotencyKeyValidator.HEADER, required = false) String key,
            @Valid @RequestBody ReceiveRequest body) {
        Outcome<ReceiptResponse> o = service.receive(actor, id, body, key);
        ResponseEntity.BodyBuilder b = ResponseEntity.status(o.replayed() ? HttpStatus.OK : HttpStatus.CREATED);
        if (o.replayed()) b.header("Idempotent-Replayed", "true");
        return b.body(o.body());
    }
}
