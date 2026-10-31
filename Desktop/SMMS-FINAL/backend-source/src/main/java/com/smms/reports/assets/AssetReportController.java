package com.smms.reports.assets;

import com.smms.entity.User;
import com.smms.reports.csv.CsvWriter;
import com.smms.util.CurrentUser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/** Asset summary and PM compliance reports; {@code format=csv} downloads the same data as CSV. */
@RestController
@RequestMapping("/api/reports")
@RequiredArgsConstructor
@Slf4j
public class AssetReportController {

    static final String READ = "hasAnyRole('system_admin','region_supervisor','hospital_supervisor','maintenance_manager',"
        + "'electrical_manager','operating_company_manager')";

    private final AssetReportService service;

    @GetMapping("/assets/summary")
    @PreAuthorize(READ)
    public ResponseEntity<?> assetSummary(
        @RequestParam(required = false) UUID centerId,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
        @RequestParam(defaultValue = "json") String format, @CurrentUser User actor) {
        LocalDate end = to != null ? to : LocalDate.now(AssetReportService.ZONE);
        LocalDate start = from != null ? from : end.minusDays(29);
        AssetReportService.AssetSummary s = service.summary(start, end, centerId, actor);
        log.info("AUDIT report assets/summary: actor={} format={}", actor.getId(), format);
        if (!isCsv(format)) return ResponseEntity.ok(s);
        List<List<?>> rows = new ArrayList<>();
        rows.add(Arrays.asList("total_assets", "", s.totalAssets(), ""));
        rows.add(Arrays.asList("decommissioned", "", s.decommissioned(), ""));
        s.byStatus().forEach(c -> rows.add(Arrays.asList("status", c.key(), c.count(), "")));
        s.byCategory().forEach(c -> rows.add(Arrays.asList("category", c.key(), c.count(), "")));
        s.byCriticality().forEach(c -> rows.add(Arrays.asList("criticality", c.key(), c.count(), "")));
        s.downtimeByAsset().forEach(d -> rows.add(Arrays.asList("downtime_hours",
            (d.assetCode() == null ? "" : d.assetCode() + " ") + d.assetName(), d.periods(), d.hours())));
        return csv("asset-summary-" + start + "_" + end + ".csv", List.of("section", "key", "count", "hours"), rows);
    }

    @GetMapping("/pm/compliance")
    @PreAuthorize(READ)
    public ResponseEntity<?> pmCompliance(
        @RequestParam(required = false) UUID centerId,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
        @RequestParam(defaultValue = "json") String format, @CurrentUser User actor) {
        LocalDate end = to != null ? to : LocalDate.now(AssetReportService.ZONE);
        LocalDate start = from != null ? from : end.minusDays(29);
        AssetReportService.PmCompliance c = service.compliance(start, end, centerId, actor);
        log.info("AUDIT report pm/compliance: actor={} format={}", actor.getId(), format);
        if (!isCsv(format)) return ResponseEntity.ok(c);
        List<List<?>> rows = new ArrayList<>();
        c.rows().forEach(r -> rows.add(Arrays.asList(r.planTitle(), r.assetCode(), r.assetName(), r.dueDate(),
            r.runStatus(), r.ticketNumber(), r.workOrderStatus(), r.completedAt(), r.outcome())));
        return csv("pm-compliance-" + start + "_" + end + ".csv", List.of("plan", "asset_code", "asset", "due_date",
            "run_status", "ticket", "work_order_status", "completed_at", "outcome"), rows);
    }

    private static boolean isCsv(String format) {
        if ("csv".equalsIgnoreCase(format)) return true;
        if ("json".equalsIgnoreCase(format)) return false;
        throw new IllegalArgumentException("format must be json or csv");
    }

    private static ResponseEntity<byte[]> csv(String name, List<String> headers, List<List<?>> rows) {
        byte[] body = new CsvWriter().toCsv(headers, rows).getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + "\"")
            .header(HttpHeaders.CACHE_CONTROL, "no-store")
            .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
            .body(body);
    }
}
