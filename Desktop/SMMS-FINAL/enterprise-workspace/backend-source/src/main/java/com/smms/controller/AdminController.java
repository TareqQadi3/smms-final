package com.smms.controller;

import com.smms.dto.request.*;
import com.smms.dto.response.CenterResponse;
import com.smms.dto.response.ControlRoomResponse;
import com.smms.dto.response.RegionResponse;
import com.smms.service.CenterService;
import com.smms.service.ControlRoomService;
import com.smms.service.RegionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {

    private final RegionService regionService;
    private final CenterService centerService;
    private final ControlRoomService controlRoomService;

    // ── Regions ──────────────────────────────────────────────
    @GetMapping("/regions")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<List<RegionResponse>> regions() {
        return ResponseEntity.ok(regionService.listAll());
    }

    @PostMapping("/regions")
    @PreAuthorize("hasRole('system_admin')")
    public ResponseEntity<RegionResponse> createRegion(@Valid @RequestBody CreateRegionRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(regionService.create(req));
    }

    @PutMapping("/regions/{id}")
    @PreAuthorize("hasRole('system_admin')")
    public ResponseEntity<RegionResponse> updateRegion(@PathVariable UUID id,
                                                       @Valid @RequestBody UpdateRegionRequest req) {
        return ResponseEntity.ok(regionService.update(id, req));
    }

    // ── Centers ───────────────────────────────────────────────
    @GetMapping("/centers")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<List<CenterResponse>> centers(@RequestParam(required = false) UUID regionId) {
        return ResponseEntity.ok(centerService.list(regionId));
    }

    @PostMapping("/centers")
    @PreAuthorize("hasAnyRole('system_admin','region_supervisor')")
    public ResponseEntity<CenterResponse> createCenter(@Valid @RequestBody CreateCenterRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(centerService.create(req));
    }

    @PutMapping("/centers/{id}")
    @PreAuthorize("hasAnyRole('system_admin','region_supervisor')")
    public ResponseEntity<CenterResponse> updateCenter(@PathVariable UUID id,
                                                       @Valid @RequestBody UpdateCenterRequest req) {
        return ResponseEntity.ok(centerService.update(id, req));
    }

    // ── Control Rooms ─────────────────────────────────────────
    @GetMapping("/control-rooms")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<List<ControlRoomResponse>> controlRooms(
            @RequestParam(required = false) UUID centerId) {
        return ResponseEntity.ok(controlRoomService.list(centerId));
    }

    @PostMapping("/control-rooms")
    @PreAuthorize("hasAnyRole('system_admin','region_supervisor','hospital_supervisor')")
    public ResponseEntity<ControlRoomResponse> createControlRoom(
            @Valid @RequestBody CreateControlRoomRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(controlRoomService.create(req));
    }

    @PutMapping("/control-rooms/{id}")
    @PreAuthorize("hasAnyRole('system_admin','region_supervisor','hospital_supervisor')")
    public ResponseEntity<ControlRoomResponse> updateControlRoom(@PathVariable UUID id,
                                                                 @Valid @RequestBody UpdateControlRoomRequest req) {
        return ResponseEntity.ok(controlRoomService.update(id, req));
    }
}
