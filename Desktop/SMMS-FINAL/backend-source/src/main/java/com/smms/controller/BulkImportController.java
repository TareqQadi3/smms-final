package com.smms.controller;

import com.smms.service.BulkImportService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

@RestController
@RequestMapping("/api/import")
@RequiredArgsConstructor
public class BulkImportController {

    private final BulkImportService importService;

    // ── Download Templates ────────────────────────────────────

    @GetMapping("/template/residents")
    @PreAuthorize("hasAnyRole('system_admin','region_supervisor','hospital_supervisor')")
    public ResponseEntity<byte[]> residentTemplate() throws Exception {
        byte[] bytes = importService.residentTemplate();
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"residents_template.xlsx\"")
            .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
            .body(bytes);
    }

    @GetMapping("/template/departments")
    @PreAuthorize("hasAnyRole('system_admin','region_supervisor','hospital_supervisor')")
    public ResponseEntity<byte[]> departmentTemplate() throws Exception {
        byte[] bytes = importService.departmentTemplate();
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"departments_template.xlsx\"")
            .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
            .body(bytes);
    }

    // ── Upload & Import ───────────────────────────────────────

    @PostMapping("/residents")
    @PreAuthorize("hasAnyRole('system_admin','region_supervisor','hospital_supervisor')")
    public ResponseEntity<Map<String, Object>> importResidents(
            @RequestParam("file") MultipartFile file) throws Exception {
        BulkImportService.ImportResult result = importService.importResidents(file);
        return ResponseEntity.ok(Map.of(
            "success", result.success(),
            "failed",  result.errors().size(),
            "errors",  result.errors()
        ));
    }

    @PostMapping("/departments")
    @PreAuthorize("hasAnyRole('system_admin','region_supervisor','hospital_supervisor')")
    public ResponseEntity<Map<String, Object>> importDepartments(
            @RequestParam("file") MultipartFile file) throws Exception {
        BulkImportService.ImportResult result = importService.importDepartments(file);
        return ResponseEntity.ok(Map.of(
            "success", result.success(),
            "failed",  result.errors().size(),
            "errors",  result.errors()
        ));
    }
}
