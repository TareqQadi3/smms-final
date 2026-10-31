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
    public ResponseEntity<List<RegionResponse>> regions(@CurrentUser User actor) {
        return ResponseEntity.ok(regionService.listAll(actor));
    }

    @PostMapping("/regions")
    @PreAuthorize("hasRole('system_admin')")
    public ResponseEntity<RegionResponse> createRegion(@Valid @RequestBody CreateRegionRequest req,
                                                       @CurrentUser User actor) {
        return ResponseEntity.status(HttpStatus.CREATED).body(regionService.create(req, actor));
    }

    @PutMapping("/regions/{id}")
    @PreAuthorize("hasRole('system_admin')")
    public ResponseEntity<RegionResponse> updateRegion(@PathVariable UUID id,
                                                       @Valid @RequestBody UpdateRegionRequest req,
                                                       @CurrentUser User actor) {
        return ResponseEntity.ok(regionService.update(id, req, actor));
    }

    // ── Centers ───────────────────────────────────────────────
    @GetMapping("/centers")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<List<CenterResponse>> centers(@RequestParam(required = false) UUID regionId,
                                                        @CurrentUser User actor) {
        return ResponseEntity.ok(centerService.list(regionId, actor));
    }

    @PostMapping("/centers")
    @PreAuthorize("hasAnyRole('system_admin','region_supervisor')")
    public ResponseEntity<CenterResponse> createCenter(@Valid @RequestBody CreateCenterRequest req,
                                                       @CurrentUser User actor) {
        return ResponseEntity.status(HttpStatus.CREATED).body(centerService.create(req, actor));
    }

    @PutMapping("/centers/{id}")
    @PreAuthorize("hasAnyRole('system_admin','region_supervisor')")
    public ResponseEntity<CenterResponse> updateCenter(@PathVariable UUID id,
                                                       @Valid @RequestBody UpdateCenterRequest req,
                                                       @CurrentUser User actor) {
        return ResponseEntity.ok(centerService.update(id, req, actor));
    }

    // ── Control Rooms ─────────────────────────────────────────
    @GetMapping("/control-rooms")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<List<ControlRoomResponse>> controlRooms(
            @RequestParam(required = false) UUID centerId,
            @CurrentUser User actor) {

        UserRole role = actor.getRole();
        if (role == UserRole.system_admin) {
            return ResponseEntity.ok(controlRoomService.list(centerId));
        }
        if (role == UserRole.region_supervisor) {
            // Only rooms of their own region; a centerId of another region matches nothing
            UUID regionId = actor.getRegion() != null ? actor.getRegion().getId() : null;
            return ResponseEntity.ok(regionId == null
                ? List.<ControlRoomResponse>of() : controlRoomService.listByRegion(regionId, centerId));
        }
        if (isCenterScopedRole(role)) {
            // The request's centerId is ignored: these roles only ever see their own center's rooms
            UUID ownCenterId = actor.getCenter() != null ? actor.getCenter().getId() : null;
            return ResponseEntity.ok(ownCenterId == null
                ? List.<ControlRoomResponse>of() : controlRoomService.list(ownCenterId));
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

    /** Roles that manage or operate inside one center: they see all CRs of that center */
    private boolean isCenterScopedRole(UserRole role) {
        return role == UserRole.hospital_supervisor
            || role == UserRole.maintenance_manager
            || role == UserRole.electrical_manager
            || role == UserRole.control_room;   // CR operators see their own center's CRs
    }

    @PostMapping("/control-rooms")
    @PreAuthorize("hasAnyRole('system_admin','region_supervisor','hospital_supervisor')")
    public ResponseEntity<ControlRoomResponse> createControlRoom(
            @Valid @RequestBody CreateControlRoomRequest req,
            @CurrentUser User actor) {
        return ResponseEntity.status(HttpStatus.CREATED).body(controlRoomService.create(req, actor));
    }

    @PutMapping("/control-rooms/{id}")
    @PreAuthorize("hasAnyRole('system_admin','region_supervisor','hospital_supervisor')")
    public ResponseEntity<ControlRoomResponse> updateControlRoom(@PathVariable UUID id,
                                                                 @Valid @RequestBody UpdateControlRoomRequest req,
                                                                 @CurrentUser User actor) {
        return ResponseEntity.ok(controlRoomService.update(id, req, actor));
    }
}
