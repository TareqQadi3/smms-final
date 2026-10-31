package com.smms.reports.inventory;

import com.smms.entity.User;
import com.smms.reports.csv.CsvWriter;
import com.smms.reports.inventory.InventoryReportService.Report;
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
import java.util.UUID;

/** Inventory and purchasing reports; {@code format=csv} downloads the same table as formula-safe CSV. */
@RestController
@RequestMapping("/api/reports")
@RequiredArgsConstructor
public class InventoryReportController {

    private static final String INVENTORY = "hasAnyRole('system_admin','region_supervisor','hospital_supervisor',"
        + "'maintenance_manager','electrical_manager','operating_company_manager','warehouse')";
    private static final String PURCHASING = "hasAnyRole('system_admin','region_supervisor','hospital_supervisor',"
        + "'maintenance_manager','electrical_manager','operating_company_manager')";

    private final InventoryReportService service;

    @GetMapping("/inventory/stock")
    @PreAuthorize(INVENTORY)
    public ResponseEntity<?> stock(@CurrentUser User actor, @RequestParam(required = false) UUID warehouseId,
                                   @RequestParam(required = false) UUID centerId,
                                   @RequestParam(defaultValue = "json") String format) {
        format(format);
        return respond(service.stock(actor, warehouseId, centerId), format);
    }

    @GetMapping("/inventory/movements")
    @PreAuthorize(INVENTORY)
    public ResponseEntity<?> movements(@CurrentUser User actor,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) UUID warehouseId, @RequestParam(required = false) UUID itemId,
            @RequestParam(required = false) String kind, @RequestParam(required = false) UUID centerId,
            @RequestParam(defaultValue = "json") String format) {
        format(format);
        return respond(service.movements(actor, from, to, warehouseId, itemId, kind, centerId), format);
    }

    @GetMapping("/inventory/consumption")
    @PreAuthorize(INVENTORY)
    public ResponseEntity<?> consumption(@CurrentUser User actor,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "workOrder") String groupBy, @RequestParam(required = false) UUID centerId,
            @RequestParam(defaultValue = "json") String format) {
        format(format);
        return respond(service.consumption(actor, from, to, groupBy, centerId), format);
    }

    @GetMapping("/inventory/open-material-requests")
    @PreAuthorize(INVENTORY)
    public ResponseEntity<?> openMaterialRequests(@CurrentUser User actor,
            @RequestParam(required = false) UUID centerId, @RequestParam(defaultValue = "json") String format) {
        format(format);
        return respond(service.openMaterialRequests(actor, centerId), format);
    }

    @GetMapping("/purchasing/orders")
    @PreAuthorize(PURCHASING)
    public ResponseEntity<?> purchaseOrders(@CurrentUser User actor,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String status, @RequestParam(required = false) UUID centerId,
            @RequestParam(defaultValue = "json") String format) {
        format(format);
        return respond(service.purchaseOrders(actor, from, to, status, centerId), format);
    }

    private static void format(String f) {
        if (!f.equals("json") && !f.equals("csv")) throw new IllegalArgumentException("format must be json or csv");
    }

    private static ResponseEntity<?> respond(Report r, String format) {
        if (format.equals("csv")) {
            String csv = new CsvWriter().toCsv(r.columns(), r.rows());
            String suffix = r.from() == null ? "" : "-" + r.from() + "-" + r.to();
            return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + r.name() + suffix + ".csv\"")
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(csv.getBytes(StandardCharsets.UTF_8));
        }
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store").body(r);
    }
}
