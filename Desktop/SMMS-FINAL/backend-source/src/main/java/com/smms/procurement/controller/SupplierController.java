package com.smms.procurement.controller;

import com.smms.procurement.dto.SupplierDtos.SupplierRequest;
import com.smms.procurement.dto.SupplierDtos.SupplierResponse;
import com.smms.procurement.service.SupplierService;
import com.smms.entity.User;
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
@RequestMapping("/api/procurement/suppliers")
@RequiredArgsConstructor
public class SupplierController {

    private static final String READ = "hasAnyRole('system_admin','region_supervisor','hospital_supervisor',"
        + "'maintenance_manager','operating_company_manager','warehouse')";
    private static final String WRITE = "hasAnyRole('system_admin','maintenance_manager','operating_company_manager')";

    private final SupplierService service;

    @GetMapping
    @PreAuthorize(READ)
    public Page<SupplierResponse> list(@CurrentUser User actor,
                                       @RequestParam(required = false) String q,
                                       @RequestParam(required = false) Boolean active,
                                       @RequestParam(defaultValue = "0") int page,
                                       @RequestParam(defaultValue = "20") int size,
                                       @RequestParam(required = false) List<String> sort) {
        return service.list(actor, q, active, SupplierService.pageable(page, size, sort));
    }

    @GetMapping("/{id}")
    @PreAuthorize(READ)
    public SupplierResponse get(@CurrentUser User actor, @PathVariable UUID id) {
        return service.get(actor, id);
    }

    @PostMapping
    @PreAuthorize(WRITE)
    public ResponseEntity<SupplierResponse> create(@CurrentUser User actor, @Valid @RequestBody SupplierRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(actor, req));
    }

    @PutMapping("/{id}")
    @PreAuthorize(WRITE)
    public SupplierResponse update(@CurrentUser User actor, @PathVariable UUID id,
                                   @Valid @RequestBody SupplierRequest req) {
        return service.update(actor, id, req);
    }

    @PostMapping("/{id}/deactivate")
    @PreAuthorize(WRITE)
    public SupplierResponse deactivate(@CurrentUser User actor, @PathVariable UUID id) {
        return service.deactivate(actor, id);
    }
}
