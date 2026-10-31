package com.smms.diagnostics;

import com.smms.controller.TenantController;
import com.smms.util.PageRequests;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only wrong-tenant diagnostics. Platform administrator only (a tenant-bound system_admin and
 * every other role is refused). Responses contain ids and counts only, never names or emails, and
 * nothing here can modify data.
 */
@RestController
@RequestMapping("/api/platform/diagnostics")
@RequiredArgsConstructor
@PreAuthorize(TenantController.PLATFORM_ADMIN_ONLY)
public class TenantDiagnosticsController {

    private final TenantDiagnosticsService service;

    @GetMapping("/summary")
    public ResponseEntity<TenantDiagnosticsService.Summary> summary() {
        return ResponseEntity.ok(service.summary());
    }

    @GetMapping("/tenant-mismatches")
    public ResponseEntity<TenantDiagnosticsService.MismatchPage> mismatches(
            @RequestParam String check,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        // PageRequests enforces page >= 0, size >= 1 and clamps size to 100 (400 on violation)
        var pageable = PageRequests.of(page, size, null, java.util.Map.of(), org.springframework.data.domain.Sort.unsorted());
        return ResponseEntity.ok(service.mismatchPage(check, pageable.getPageNumber(), pageable.getPageSize()));
    }
}
