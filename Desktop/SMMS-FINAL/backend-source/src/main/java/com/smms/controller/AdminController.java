package com.smms.controller;

import com.smms.dto.request.*;
import com.smms.dto.response.CenterResponse;
import com.smms.dto.response.ControlRoomResponse;
import com.smms.dto.response.RegionResponse;
import com.smms.entity.User;
import com.smms.enums.UserRole;
import com.smms.repository.UserControlRoomAccessRepository;
import com.smms.service.CenterService;
import com.smms.service.ControlRoomService;
import com.smms.service.RegionService;
import com.smms.util.CurrentUser;
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
    private final UserControlRoomAccessRepository crAccessRepo;

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
            @RequestParam(required = false) UUID centerId,
            @CurrentUser User actor) {

        // Roles that see ALL CRs in their scope (managers & operators)
        if (isCenterScopedRole(actor.getRole())) {
            UUID effectiveCenterId;
            if (actor.getRole() == UserRole.system_admin
                    || actor.getRole() == UserRole.region_supervisor) {
                effectiveCenterId = centerId; // can pass any centerId
            } else {
                effectiveCenterId = actor.getCenter() != null
                    ? actor.getCenter().getId() : centerId;
            }
            return ResponseEntity.ok(controlRoomService.list(effectiveCenterId));
        }

        // resident / department / other / technician / warehouse:
        // see ONLY the CRs explicitly assigned to them
        var accessList = crAccessRepo.findByUserId(actor.getId());
        if (accessList.isEmpty()) {
            return ResponseEntity.ok(List.of());
        }
        List<UUID> crIds = accessList.stream()
            .map(a -> a.getControlRoom().getId())
            .toList();
        return ResponseEntity.ok(controlRoomService.listByIds(crIds));
    }

    /** Roles that manage or operate — see all CRs in their center */
    private boolean isCenterScopedRole(UserRole role) {
        return role == UserRole.system_admin
            || role == UserRole.region_supervisor
            || role == UserRole.hospital_supervisor
            || role == UserRole.maintenance_manager
            || role == UserRole.electrical_manager
            || role == UserRole.control_room;   // CR operators see their own center's CRs
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
