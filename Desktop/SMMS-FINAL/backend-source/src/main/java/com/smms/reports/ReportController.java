package com.smms.reports;

import com.smms.entity.User;
import com.smms.reports.ReportModels.*;
import com.smms.reports.csv.CsvWriter;
import com.smms.util.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Operational reports. Read-only, tenant and location scoped, roles per the permission matrix:
 * system_admin (tenant, or all tenants for the platform admin), region_supervisor (region),
 * hospital_supervisor / maintenance_manager / electrical_manager / operating_company_manager (center).
 * {@code format=csv} returns the same data as a download (formula-injection safe, at most 50,000 rows).
 */
@RestController
@RequestMapping("/api/reports")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('system_admin','region_supervisor','hospital_supervisor','maintenance_manager','electrical_manager','operating_company_manager')")
public class ReportController {

    private final ReportService service;

    @GetMapping("/work-orders/volume")
    public ResponseEntity<?> volume(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                    @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                    @RequestParam(required = false) UUID centerId,
                                    @RequestParam(required = false) UUID regionId,
                                    @RequestParam(required = false) String groupBy,
                                    @RequestParam(defaultValue = "json") String format,
                                    @CurrentUser User actor) {
        boolean csv = isCsv(format);
        var req = service.resolve(actor, from, to, centerId, regionId);
        Volume v = service.volume(req, groupBy);
        if (!csv) return ResponseEntity.ok(v);
        List<List<?>> rows = new ArrayList<>();
        v.groups().forEach(g -> rows.add(List.of(g.key(), g.label(), g.count())));
        return csv("work-order-volume", req.range(), List.of(v.groupBy(), "label", "count"), rows);
    }

    @GetMapping("/work-orders/turnaround")
    public ResponseEntity<?> turnaround(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                        @RequestParam(required = false) UUID centerId,
                                        @RequestParam(required = false) UUID regionId,
                                        @RequestParam(required = false) String groupBy,
                                        @RequestParam(defaultValue = "json") String format,
                                        @CurrentUser User actor) {
        boolean csv = isCsv(format);
        var req = service.resolve(actor, from, to, centerId, regionId);
        TurnaroundReport t = service.turnaround(req, groupBy);
        if (!csv) return ResponseEntity.ok(t.rows());
        List<List<?>> rows = new ArrayList<>();
        t.rows().forEach(x -> rows.add(List.of(x.key(), x.completedCount(), nz(x.avgHours()), nz(x.p50Hours()), nz(x.p90Hours()))));
        return csv("work-order-turnaround", req.range(),
            List.of(t.groupBy(), "completedCount", "avgHours", "p50Hours", "p90Hours"), rows);
    }

    @GetMapping("/sla")
    public ResponseEntity<?> sla(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                 @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                 @RequestParam(required = false) UUID centerId,
                                 @RequestParam(required = false) UUID regionId,
                                 @RequestParam(defaultValue = "json") String format,
                                 @CurrentUser User actor) {
        boolean csv = isCsv(format);
        var req = service.resolve(actor, from, to, centerId, regionId);
        SlaReport s = service.sla(req);
        if (!csv) return ResponseEntity.ok(s.rows());
        List<List<?>> rows = new ArrayList<>();
        s.rows().forEach(x -> rows.add(List.of(x.priority(), x.due(), x.metOnTime(), x.breached(),
            nz(x.compliancePct()), nz(x.avgPausedHours()), x.openBreached())));
        return csv("sla", req.range(),
            List.of("priority", "due", "metOnTime", "breached", "compliancePct", "avgPausedHours", "openBreached"), rows);
    }

    @GetMapping("/escalations")
    public ResponseEntity<?> escalations(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                         @RequestParam(required = false) UUID centerId,
                                         @RequestParam(required = false) UUID regionId,
                                         @RequestParam(defaultValue = "json") String format,
                                         @CurrentUser User actor) {
        boolean csv = isCsv(format);
        var req = service.resolve(actor, from, to, centerId, regionId);
        EscalationReport e = service.escalations(req);
        if (!csv) return ResponseEntity.ok(e);
        List<List<?>> rows = new ArrayList<>();
        e.groups().forEach(g -> rows.add(List.of(g.level(), g.trigger() == null ? "" : g.trigger(), g.count())));
        return csv("escalations", req.range(), List.of("level", "trigger", "count"), rows);
    }

    @GetMapping("/technicians")
    public ResponseEntity<?> technicians(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                         @RequestParam(required = false) UUID centerId,
                                         @RequestParam(required = false) UUID regionId,
                                         @RequestParam(defaultValue = "0") int page,
                                         @RequestParam(defaultValue = "20") int size,
                                         @RequestParam(defaultValue = "json") String format,
                                         @CurrentUser User actor) {
        boolean csv = isCsv(format);
        var req = service.resolve(actor, from, to, centerId, regionId);
        Page<TechnicianRow> p = service.technicians(req, page, size, csv);
        if (!csv) return ResponseEntity.ok(p);
        List<List<?>> rows = new ArrayList<>();
        p.content().forEach(t -> rows.add(List.of(t.technician().fullName() == null ? "" : t.technician().fullName(),
            t.assigned(), t.completed(), t.open(), t.reopened(), nz(t.avgTurnaroundHours()),
            t.completedByPriority().critical(), t.completedByPriority().high(), t.completedByPriority().medium(),
            t.completedByPriority().low(), t.emergencyHandled())));
        return csv("technicians", req.range(), List.of("technician", "assigned", "completed", "open", "reopened",
            "avgTurnaroundHours", "completedCritical", "completedHigh", "completedMedium", "completedLow", "emergencyHandled"), rows);
    }

    /** Snapshot of open tickets past their SLA deadline (from/to are not used). */
    @GetMapping("/overdue")
    public ResponseEntity<?> overdue(@RequestParam(required = false) UUID centerId,
                                     @RequestParam(required = false) UUID regionId,
                                     @RequestParam(required = false) String priority,
                                     @RequestParam(defaultValue = "0") int page,
                                     @RequestParam(defaultValue = "20") int size,
                                     @RequestParam(defaultValue = "json") String format,
                                     @CurrentUser User actor) {
        boolean csv = isCsv(format);
        Page<OverdueRow> p = service.overdue(actor, centerId, regionId, priority, page, size, csv);
        if (!csv) return ResponseEntity.ok(p);
        List<List<?>> rows = new ArrayList<>();
        p.content().forEach(o -> rows.add(List.of(o.ticketNumber(), o.title() == null ? "" : o.title(), o.priority(), o.status(),
            o.centerName() == null ? "" : o.centerName(), o.assignedTo() == null ? "" : String.valueOf(o.assignedTo().fullName()),
            o.slaDeadline().toString(), nz(o.hoursOverdue()))));
        return csvNamed("overdue", LocalDate.now(java.time.Clock.systemUTC().withZone(ReportRange.ZONE)).toString(),
            List.of("ticketNumber", "title", "priority", "status", "center", "assignedTo", "slaDeadline", "hoursOverdue"), rows);
    }

    private static Object nz(java.math.BigDecimal v) { return v == null ? "" : v; }

    private static boolean isCsv(String format) {
        if ("json".equalsIgnoreCase(format)) return false;
        if ("csv".equalsIgnoreCase(format)) return true;
        throw new IllegalArgumentException("format must be json or csv");
    }

    private static ResponseEntity<byte[]> csv(String name, ReportRange range, List<String> headers, List<List<?>> rows) {
        return csvNamed(name, range.fileSuffix(), headers, rows);
    }

    private static ResponseEntity<byte[]> csvNamed(String name, String suffix, List<String> headers, List<List<?>> rows) {
        String body = new CsvWriter().toCsv(headers, rows); // 422 above the row cap
        return ResponseEntity.ok()
            .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + "-" + suffix + ".csv\"")
            .header(HttpHeaders.CACHE_CONTROL, "no-store")
            .body(body.getBytes(StandardCharsets.UTF_8));
    }
}
