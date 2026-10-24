package com.smms.assets;

import com.smms.pm.MaintenancePlanController;
import com.smms.reports.assets.AssetReportController;
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

/** Pins the role sets of the W3 endpoints (permission matrix) by reflection on the real controllers. */
class AssetPmEndpointRolesTest {

    private static final Pattern ROLE = Pattern.compile("'([a-z_]+)'");

    private static Map<String, Set<String>> rolesByEndpoint(Class<?> type) {
        Map<String, Set<String>> out = new TreeMap<>();
        String base = AnnotatedElementUtils.findMergedAnnotation(type, RequestMapping.class).path()[0];
        for (Method m : type.getDeclaredMethods()) {
            RequestMapping rm = AnnotatedElementUtils.findMergedAnnotation(m, RequestMapping.class);
            if (rm == null) continue;
            PreAuthorize pa = AnnotatedElementUtils.findMergedAnnotation(m, PreAuthorize.class);
            assertThat(pa).as(m.getName() + " needs @PreAuthorize").isNotNull();
            Set<String> roles = new TreeSet<>();
            Matcher mt = ROLE.matcher(pa.value());
            while (mt.find()) roles.add(mt.group(1));
            String sub = rm.path().length > 0 ? rm.path()[0] : "";
            RequestMethod verb = rm.method()[0];
            out.put(verb + " " + base + sub, roles);
        }
        return out;
    }

    private static Set<String> set(String... r) { return new TreeSet<>(Arrays.asList(r)); }

    @Test
    void assetEndpoints() {
        Map<String, Set<String>> e = rolesByEndpoint(AssetController.class);
        Set<String> read = set("system_admin", "region_supervisor", "hospital_supervisor", "maintenance_manager",
            "electrical_manager", "operating_company_manager", "control_room", "warehouse", "technician", "department");
        Set<String> write = set("system_admin", "hospital_supervisor", "maintenance_manager", "electrical_manager");
        assertThat(e.get("GET /api/assets")).isEqualTo(read);
        assertThat(e.get("GET /api/assets/{id}")).isEqualTo(read);
        assertThat(e.get("GET /api/assets/{id}/work-orders")).isEqualTo(read);
        assertThat(e.get("GET /api/assets/{id}/downtime")).isEqualTo(read);
        assertThat(e.get("POST /api/assets")).isEqualTo(write);
        assertThat(e.get("PUT /api/assets/{id}")).isEqualTo(write);
        assertThat(e.get("POST /api/assets/{id}/decommission")).isEqualTo(write);
        assertThat(e.get("GET /api/assets/{id}/materials")).isEqualTo(set("system_admin", "region_supervisor",
            "hospital_supervisor", "maintenance_manager", "electrical_manager", "operating_company_manager", "warehouse", "technician"));
        Set<String> dt = set("system_admin", "hospital_supervisor", "maintenance_manager", "electrical_manager", "control_room", "technician");
        assertThat(e.get("POST /api/assets/{id}/downtime")).isEqualTo(dt);
        assertThat(e.get("POST /api/assets/{id}/downtime/{dtId}/end")).isEqualTo(dt);
        // end users and other never appear
        e.values().forEach(r -> assertThat(r).doesNotContain("resident", "other"));
    }

    @Test
    void planAndReportEndpoints() {
        Map<String, Set<String>> e = rolesByEndpoint(MaintenancePlanController.class);
        Set<String> read = set("system_admin", "region_supervisor", "hospital_supervisor", "maintenance_manager",
            "electrical_manager", "operating_company_manager", "control_room");
        Set<String> write = set("system_admin", "hospital_supervisor", "maintenance_manager", "electrical_manager");
        e.forEach((k, v) -> assertThat(v).as(k).isEqualTo(k.startsWith("GET") ? read : write));
        assertThat(e).hasSize(10);

        Map<String, Set<String>> r = rolesByEndpoint(AssetReportController.class);
        assertThat(r.values()).allSatisfy(v -> assertThat(v).isEqualTo(set("system_admin", "region_supervisor",
            "hospital_supervisor", "maintenance_manager", "electrical_manager", "operating_company_manager")));
        assertThat(r.keySet()).containsExactlyInAnyOrder("GET /api/reports/assets/summary", "GET /api/reports/pm/compliance");
    }
}
