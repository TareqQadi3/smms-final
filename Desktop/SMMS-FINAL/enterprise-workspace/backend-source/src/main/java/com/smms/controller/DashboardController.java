package com.smms.controller;

import com.smms.entity.User;
import com.smms.service.DashboardService;
import com.smms.util.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final DashboardService dashboardService;

    @GetMapping("/control-room")
    @PreAuthorize("hasRole('control_room')")
    public ResponseEntity<?> controlRoom(@CurrentUser User actor) {
        return ResponseEntity.ok(dashboardService.controlRoomStats(actor));
    }

    @GetMapping("/technician")
    @PreAuthorize("hasRole('technician')")
    public ResponseEntity<?> technician(@CurrentUser User actor) {
        return ResponseEntity.ok(dashboardService.technicianStats(actor));
    }

    @GetMapping("/management")
    @PreAuthorize("hasAnyRole('maintenance_manager','hospital_supervisor','region_supervisor','system_admin')")
    public ResponseEntity<?> management(@CurrentUser User actor,
                                        @RequestParam(required = false) UUID centerId) {
        return ResponseEntity.ok(dashboardService.managementStats(actor, centerId));
    }
}
