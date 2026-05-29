package com.smms.controller;

import com.smms.dto.request.CreateMaintenanceTypeRequest;
import com.smms.dto.response.MaintenanceTypeResponse;
import com.smms.enums.ControlRoomType;
import com.smms.service.MaintenanceTypeService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/maintenance-types")
@RequiredArgsConstructor
public class MaintenanceTypeController {

    private final MaintenanceTypeService service;

    /** Public (authenticated) — filtered by control room. Used in new-ticket form. */
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<List<MaintenanceTypeResponse>> list(
            @RequestParam(required = false) UUID controlRoomId,
            @RequestParam(required = false) ControlRoomType controlRoomType) {
        if (controlRoomId != null) {
            return ResponseEntity.ok(service.listByControlRoom(controlRoomId));
        }
        if (controlRoomType != null) {
            return ResponseEntity.ok(service.listByType(controlRoomType));
        }
        return ResponseEntity.ok(service.listAll());
    }

    /** Admin-only CRUD */
    @PostMapping
    @PreAuthorize("hasRole('system_admin')")
    public ResponseEntity<MaintenanceTypeResponse> create(
            @Valid @RequestBody CreateMaintenanceTypeRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(req));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('system_admin')")
    public ResponseEntity<MaintenanceTypeResponse> update(
            @PathVariable UUID id,
            @Valid @RequestBody CreateMaintenanceTypeRequest req) {
        return ResponseEntity.ok(service.update(id, req));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('system_admin')")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
