package com.smms.pm;

import com.smms.entity.User;
import com.smms.pm.PlanDtos.*;
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

/** Preventive maintenance plans (FP1 W3). */
@RestController
@RequestMapping("/api/maintenance-plans")
@RequiredArgsConstructor
public class MaintenancePlanController {

    static final String READ = "hasAnyRole('system_admin','region_supervisor','hospital_supervisor','maintenance_manager',"
        + "'electrical_manager','operating_company_manager','control_room')";
    static final String WRITE = "hasAnyRole('system_admin','hospital_supervisor','maintenance_manager','electrical_manager')";

    private final MaintenancePlanService service;

    @GetMapping
    @PreAuthorize(READ)
    public ResponseEntity<Page<PlanResponse>> list(
        @RequestParam(required = false) UUID centerId, @RequestParam(required = false) UUID assetId,
        @RequestParam(required = false) Boolean active, @RequestParam(defaultValue = "false") boolean overdue,
        @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size,
        @RequestParam(required = false) List<String> sort, @CurrentUser User actor) {
        return ResponseEntity.ok(service.list(centerId, assetId, active, overdue, page, size, sort, actor));
    }

    @GetMapping("/overdue")
    @PreAuthorize(READ)
    public ResponseEntity<Page<PlanResponse>> overdue(
        @RequestParam(required = false) UUID centerId,
        @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size,
        @CurrentUser User actor) {
        return ResponseEntity.ok(service.list(centerId, null, true, true, page, size, null, actor));
    }

    @GetMapping("/{id}")
    @PreAuthorize(READ)
    public ResponseEntity<PlanResponse> get(@PathVariable UUID id, @CurrentUser User actor) {
        return ResponseEntity.ok(service.get(id, actor));
    }

    @GetMapping("/{id}/runs")
    @PreAuthorize(READ)
    public ResponseEntity<Page<RunResponse>> runs(@PathVariable UUID id, @RequestParam(defaultValue = "0") int page,
                                                  @RequestParam(defaultValue = "20") int size, @CurrentUser User actor) {
        return ResponseEntity.ok(service.runs(id, page, size, actor));
    }

    @PostMapping
    @PreAuthorize(WRITE)
    public ResponseEntity<PlanResponse> create(@Valid @RequestBody CreatePlanRequest req, @CurrentUser User actor) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(req, actor));
    }

    @PutMapping("/{id}")
    @PreAuthorize(WRITE)
    public ResponseEntity<PlanResponse> update(@PathVariable UUID id, @Valid @RequestBody UpdatePlanRequest req,
                                               @CurrentUser User actor) {
        return ResponseEntity.ok(service.update(id, req, actor));
    }

    @PostMapping("/{id}/activate")
    @PreAuthorize(WRITE)
    public ResponseEntity<PlanResponse> activate(@PathVariable UUID id, @CurrentUser User actor) {
        return ResponseEntity.ok(service.activate(id, actor));
    }

    @PostMapping("/{id}/deactivate")
    @PreAuthorize(WRITE)
    public ResponseEntity<PlanResponse> deactivate(@PathVariable UUID id, @CurrentUser User actor) {
        return ResponseEntity.ok(service.deactivate(id, actor));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize(WRITE)
    public ResponseEntity<Void> delete(@PathVariable UUID id, @CurrentUser User actor) {
        service.delete(id, actor);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/generate-now")
    @PreAuthorize(WRITE)
    public ResponseEntity<GenerateResponse> generateNow(@PathVariable UUID id, @CurrentUser User actor) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.generateNow(id, actor));
    }
}
