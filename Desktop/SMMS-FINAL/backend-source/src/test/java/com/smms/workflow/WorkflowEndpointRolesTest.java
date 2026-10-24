package com.smms.workflow;

import com.smms.controller.WorkOrderController;
import com.smms.department.DepartmentController;
import com.smms.department.UserDepartmentController;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** Pins the role sets of the W1 endpoints (privilege-escalation guard); the matrix of section 2.11. */
class WorkflowEndpointRolesTest {

    private static Set<String> roles(Class<?> type, String method) {
        Method m = Arrays.stream(type.getDeclaredMethods()).filter(x -> x.getName().equals(method)).findFirst().orElseThrow();
        PreAuthorize pa = m.getAnnotation(PreAuthorize.class);
        assertThat(pa).as(method + " needs @PreAuthorize").isNotNull();
        Set<String> out = new TreeSet<>();
        Matcher mt = Pattern.compile("'([a-z_]+)'").matcher(pa.value());
        while (mt.find()) out.add(mt.group(1));
        return out;
    }

    private static Set<String> set(String... r) { return new TreeSet<>(Arrays.asList(r)); }

    @Test
    void workflowEndpoints() {
        Class<?> c = WorkOrderWorkflowController.class;
        assertThat(roles(c, "escalate")).isEqualTo(set("control_room", "maintenance_manager", "hospital_supervisor",
            "electrical_manager", "system_admin"));
        assertThat(roles(c, "escalations")).doesNotContain("resident", "department", "technician", "other");
        assertThat(roles(c, "declareEmergency")).isEqualTo(set("control_room", "maintenance_manager", "hospital_supervisor", "system_admin"));
        assertThat(roles(c, "clearEmergency")).isEqualTo(set("maintenance_manager", "hospital_supervisor", "system_admin"));
        assertThat(roles(c, "note")).isEqualTo(set("technician", "control_room", "maintenance_manager", "hospital_supervisor", "system_admin"));
        assertThat(roles(c, "rating")).doesNotContain("technician", "other");
    }

    @Test
    void departmentEndpoints() {
        Set<String> read = set("system_admin", "region_supervisor", "hospital_supervisor", "maintenance_manager",
            "electrical_manager", "operating_company_manager", "control_room");
        Set<String> write = set("system_admin", "region_supervisor", "hospital_supervisor");
        Class<?> c = DepartmentController.class;
        for (String m : new String[]{"list", "get", "members"}) assertThat(roles(c, m)).as(m).isEqualTo(read);
        for (String m : new String[]{"create", "update", "deactivate"}) assertThat(roles(c, m)).as(m).isEqualTo(write);
        assertThat(roles(UserDepartmentController.class, "link")).isEqualTo(write);
    }

    @Test
    void existingEndpointsKeepTheirRoles() {
        assertThat(roles(WorkOrderController.class, "requestInfo")).isEqualTo(set("control_room", "maintenance_manager"));
        assertThat(roles(WorkOrderController.class, "approvalDecision")).isEqualTo(set("operating_company_manager"));
        assertThat(roles(WorkOrderController.class, "warehouseAction")).isEqualTo(set("warehouse"));
    }
}
