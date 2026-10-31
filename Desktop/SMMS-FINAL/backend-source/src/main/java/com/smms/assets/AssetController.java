package com.smms.assets;

import com.smms.dto.request.CreateAssetRequest;
import com.smms.dto.response.AssetResponse;
import com.smms.entity.User;
import com.smms.enums.AssetStatus;
import com.smms.util.CurrentUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Asset register API (FP1 W3). Scope and role rules live in AssetAccessPolicy / DowntimeService. */
@RestController
@RequestMapping("/api/assets")
@RequiredArgsConstructor
public class AssetController {

    static final String READ = "hasAnyRole('system_admin','region_supervisor','hospital_supervisor','maintenance_manager',"
        + "'electrical_manager','operating_company_manager','control_room','warehouse','technician','department')";
    static final String WRITE = "hasAnyRole('system_admin','hospital_supervisor','maintenance_manager','electrical_manager')";
    static final String MATERIALS = "hasAnyRole('system_admin','region_supervisor','hospital_supervisor','maintenance_manager',"
        + "'electrical_manager','operating_company_manager','warehouse','technician')";
    static final String DOWNTIME_WRITE = "hasAnyRole('system_admin','hospital_supervisor','maintenance_manager',"
        + "'electrical_manager','control_room','technician')";

    public record DowntimeResponse(UUID id, UUID assetId, UUID workOrderId, Instant startedAt, Instant endedAt,
                                   String reason, UUID recordedById, UUID endedById, Long durationSeconds) {
        static DowntimeResponse of(AssetDowntime d) {
            Long secs = d.getEndedAt() == null ? null : d.getEndedAt().getEpochSecond() - d.getStartedAt().getEpochSecond();
            return new DowntimeResponse(d.getId(), d.getAssetId(), d.getWorkOrderId(), d.getStartedAt(), d.getEndedAt(),
                d.getReason(), d.getRecordedById(), d.getEndedById(), secs);
        }
    }

    private final AssetService assetService;
    private final AssetHistoryService history;
    private final DowntimeService downtime;

    @GetMapping
    @PreAuthorize(READ)
    public ResponseEntity<Page<AssetResponse>> list(
        @RequestParam(required = false) UUID centerId,
        @RequestParam(required = false) UUID departmentId,
        @RequestParam(required = false) AssetStatus status,
        @RequestParam(required = false) String category,
        @RequestParam(required = false) String q,
        @RequestParam(defaultValue = "false") boolean includeDecommissioned,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size,
        @RequestParam(required = false) List<String> sort,
        @CurrentUser User actor) {
        return ResponseEntity.ok(assetService.list(centerId, departmentId, status, category, q,
            includeDecommissioned, page, size, sort, actor));
    }

    @GetMapping("/{id}")
    @PreAuthorize(READ)
    public ResponseEntity<AssetResponse> get(@PathVariable UUID id, @CurrentUser User actor) {
        return ResponseEntity.ok(assetService.get(id, actor));
    }

    @PostMapping
    @PreAuthorize(WRITE)
    public ResponseEntity<AssetResponse> create(@Valid @RequestBody CreateAssetRequest req, @CurrentUser User actor) {
        return ResponseEntity.status(HttpStatus.CREATED).body(assetService.create(req, actor));
    }

    @PutMapping("/{id}")
    @PreAuthorize(WRITE)
    public ResponseEntity<AssetResponse> update(@PathVariable UUID id, @Valid @RequestBody UpdateAssetRequest req,
                                                @CurrentUser User actor) {
        return ResponseEntity.ok(assetService.update(id, req, actor));
    }

    @PostMapping("/{id}/decommission")
    @PreAuthorize(WRITE)
    public ResponseEntity<AssetResponse> decommission(@PathVariable UUID id, @Valid @RequestBody DecommissionRequest req,
                                                      @CurrentUser User actor) {
        return ResponseEntity.ok(assetService.decommission(id, req.reason(), actor));
    }

    @GetMapping("/{id}/work-orders")
    @PreAuthorize(READ)
    public ResponseEntity<Page<AssetHistoryService.AssetWorkOrderRow>> workOrders(
        @PathVariable UUID id, @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size,
        @CurrentUser User actor) {
        assetService.requireReadable(id, actor);
        return ResponseEntity.ok(history.workOrders(id, page, size, actor));
    }

    @GetMapping("/{id}/materials")
    @PreAuthorize(MATERIALS)
    public ResponseEntity<List<AssetHistoryService.MaterialRow>> materials(
        @PathVariable UUID id,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
        @CurrentUser User actor) {
        assetService.requireReadable(id, actor);
        Instant f = from == null ? null : from.atStartOfDay(AssetService.ZONE).toInstant();
        Instant t = to == null ? null : to.plusDays(1).atStartOfDay(AssetService.ZONE).toInstant();
        if (f != null && t != null && !t.isAfter(f)) throw new IllegalArgumentException("to cannot be before from");
        return ResponseEntity.ok(history.materials(id, f, t, actor));
    }

    @GetMapping("/{id}/downtime")
    @PreAuthorize(READ)
    public ResponseEntity<Page<DowntimeResponse>> downtimeList(
        @PathVariable UUID id, @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size,
        @CurrentUser User actor) {
        return ResponseEntity.ok(downtime.list(id, page, size, actor).map(DowntimeResponse::of));
    }

    @PostMapping("/{id}/downtime")
    @PreAuthorize(DOWNTIME_WRITE)
    public ResponseEntity<DowntimeResponse> startDowntime(@PathVariable UUID id,
                                                          @Valid @RequestBody DowntimeService.StartRequest req,
                                                          @CurrentUser User actor) {
        return ResponseEntity.status(HttpStatus.CREATED).body(DowntimeResponse.of(downtime.start(id, req, actor)));
    }

    @PostMapping("/{id}/downtime/{dtId}/end")
    @PreAuthorize(DOWNTIME_WRITE)
    public ResponseEntity<DowntimeResponse> endDowntime(@PathVariable UUID id, @PathVariable UUID dtId,
                                                        @RequestBody(required = false) DowntimeService.EndRequest req,
                                                        @CurrentUser User actor) {
        return ResponseEntity.ok(DowntimeResponse.of(downtime.end(id, dtId, req, actor)));
    }
}
