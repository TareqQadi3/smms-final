package com.smms.procurement;

import com.smms.materials.controller.MaterialRequestController;
import com.smms.procurement.controller.PurchaseOrderController;
import com.smms.procurement.controller.SupplierController;
import com.smms.reports.inventory.InventoryReportController;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

import java.lang.reflect.Method;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the role sets of every procurement / material-request / report endpoint to the permission
 * matrix (plan 2.11) by reading the real controllers. End-user roles never appear.
 */
class ProcurementEndpointAuthzTest {

    private static final Pattern ROLE = Pattern.compile("'([a-z_]+)'");
    private static final Set<String> NEVER = Set.of("resident", "department", "other");

    private static Map<String, Set<String>> endpoints() {
        Map<String, Set<String>> out = new TreeMap<>();
        for (Class<?> c : List.of(SupplierController.class, PurchaseOrderController.class,
                MaterialRequestController.class, InventoryReportController.class)) {
            RequestMapping base = AnnotatedElementUtils.findMergedAnnotation(c, RequestMapping.class);
            String prefix = base == null || base.path().length == 0 ? "" : base.path()[0];
            for (Method m : c.getDeclaredMethods()) {
                RequestMapping rm = AnnotatedElementUtils.findMergedAnnotation(m, RequestMapping.class);
                if (rm == null) continue;
                PreAuthorize pa = AnnotatedElementUtils.findMergedAnnotation(m, PreAuthorize.class);
                assertThat(pa).as(c.getSimpleName() + "." + m.getName() + " needs @PreAuthorize").isNotNull();
                assertThat(pa.value()).as(m.getName()).matches(".*hasA?n?y?Role\\(.*");
                Set<String> roles = new TreeSet<>();
                Matcher mt = ROLE.matcher(pa.value());
                while (mt.find()) roles.add(mt.group(1));
                RequestMethod verb = rm.method()[0];
                String path = prefix + (rm.path().length == 0 ? "" : rm.path()[0]);
                out.put(verb + " " + path, roles);
            }
        }
        return out;
    }

    private static Set<String> r(String... roles) { return new TreeSet<>(Arrays.asList(roles)); }

    @Test
    void everyHandlerNamesRolesAndNeverEndUsers() {
        Map<String, Set<String>> e = endpoints();
        assertThat(e).hasSize(5 + 10 + 13 + 5);
        e.forEach((k, roles) -> assertThat(roles).as(k).isNotEmpty().doesNotContainAnyElementsOf(NEVER));
    }

    @Test
    void supplierRoles() {
        Map<String, Set<String>> e = endpoints();
        Set<String> read = r("system_admin", "region_supervisor", "hospital_supervisor", "maintenance_manager",
            "operating_company_manager", "warehouse");
        Set<String> write = r("system_admin", "maintenance_manager", "operating_company_manager");
        assertThat(e.get("GET /api/procurement/suppliers")).isEqualTo(read);
        assertThat(e.get("GET /api/procurement/suppliers/{id}")).isEqualTo(read);
        assertThat(e.get("POST /api/procurement/suppliers")).isEqualTo(write);
        assertThat(e.get("PUT /api/procurement/suppliers/{id}")).isEqualTo(write);
        assertThat(e.get("POST /api/procurement/suppliers/{id}/deactivate")).isEqualTo(write);
    }

    @Test
    void purchaseOrderRoles() {
        Map<String, Set<String>> e = endpoints();
        String b = "/api/procurement/purchase-orders";
        assertThat(e.get("GET " + b)).isEqualTo(r("system_admin", "region_supervisor", "hospital_supervisor",
            "maintenance_manager", "operating_company_manager", "warehouse"));
        for (String k : List.of("POST " + b, "PUT " + b + "/{id}", "POST " + b + "/{id}/submit")) {
            assertThat(e.get(k)).as(k).isEqualTo(r("warehouse", "maintenance_manager"));
        }
        for (String k : List.of("POST " + b + "/{id}/approve", "POST " + b + "/{id}/reject")) {
            assertThat(e.get(k)).as(k).isEqualTo(r("operating_company_manager", "maintenance_manager", "system_admin"));
        }
        assertThat(e.get("POST " + b + "/{id}/receive")).isEqualTo(r("warehouse"));
        assertThat(e.get("POST " + b + "/{id}/close")).isEqualTo(r("maintenance_manager", "operating_company_manager"));
        assertThat(e.get("POST " + b + "/{id}/cancel")).isEqualTo(r("warehouse", "maintenance_manager",
            "operating_company_manager", "system_admin"));
    }

    @Test
    void materialRequestRoles() {
        Map<String, Set<String>> e = endpoints();
        String b = "/api/material-requests";
        assertThat(e.get("POST /api/work-orders/{woId}/material-requests")).isEqualTo(r("technician", "control_room", "maintenance_manager"));
        Set<String> readers = r("system_admin", "region_supervisor", "hospital_supervisor", "maintenance_manager",
            "electrical_manager", "operating_company_manager", "warehouse", "control_room", "technician");
        assertThat(e.get("GET /api/work-orders/{woId}/material-requests")).isEqualTo(readers);
        assertThat(e.get("GET " + b)).isEqualTo(readers);
        assertThat(e.get("GET " + b + "/{id}")).isEqualTo(readers);
        for (String a : List.of("approve", "reject")) {
            assertThat(e.get("POST " + b + "/{id}/" + a)).as(a).isEqualTo(r("maintenance_manager", "operating_company_manager", "electrical_manager"));
        }
        for (String a : List.of("reserve", "issue", "return")) {
            assertThat(e.get("POST " + b + "/{id}/" + a)).as(a).isEqualTo(r("warehouse"));
        }
        assertThat(e.get("POST " + b + "/{id}/consume")).isEqualTo(r("technician"));
        assertThat(e.get("POST " + b + "/{id}/close")).isEqualTo(r("warehouse", "maintenance_manager"));
        assertThat(e.get("POST " + b + "/{id}/purchase-order")).isEqualTo(r("warehouse", "maintenance_manager"));
        assertThat(e.get("POST " + b + "/{id}/cancel")).isEqualTo(r("technician", "control_room", "maintenance_manager", "warehouse", "system_admin"));
    }

    @Test
    void reportRoles() {
        Map<String, Set<String>> e = endpoints();
        Set<String> inv = r("system_admin", "region_supervisor", "hospital_supervisor", "maintenance_manager",
            "electrical_manager", "operating_company_manager", "warehouse");
        for (String k : List.of("stock", "movements", "consumption", "open-material-requests")) {
            assertThat(e.get("GET /api/reports/inventory/" + k)).as(k).isEqualTo(inv);
        }
        // purchasing reports: no warehouse role
        assertThat(e.get("GET /api/reports/purchasing/orders")).isEqualTo(r("system_admin", "region_supervisor",
            "hospital_supervisor", "maintenance_manager", "electrical_manager", "operating_company_manager"));
    }

    @Test
    void serviceRoleConstantsMatchTheControllers() {
        Map<String, Set<String>> e = endpoints();
        assertThat(names(com.smms.procurement.service.PurchaseOrderService.APPROVE))
            .isEqualTo(e.get("POST /api/procurement/purchase-orders/{id}/approve"));
        assertThat(names(com.smms.procurement.service.PurchaseOrderService.RECEIVE))
            .isEqualTo(e.get("POST /api/procurement/purchase-orders/{id}/receive"));
        assertThat(names(com.smms.procurement.service.SupplierService.WRITE))
            .isEqualTo(e.get("POST /api/procurement/suppliers"));
        assertThat(names(com.smms.materials.service.MaterialRequestService.DECIDE))
            .isEqualTo(e.get("POST /api/material-requests/{id}/approve"));
        assertThat(names(com.smms.materials.service.MaterialRequestService.READ))
            .isEqualTo(e.get("GET /api/material-requests"));
        assertThat(names(com.smms.reports.inventory.InventoryReportService.PURCHASING_ROLES))
            .isEqualTo(e.get("GET /api/reports/purchasing/orders"));
    }

    private static Set<String> names(com.smms.enums.UserRole[] roles) {
        Set<String> s = new TreeSet<>();
        for (var r : roles) s.add(r.name());
        return s;
    }
}
