package com.smms.reports;

import com.smms.entity.User;
import com.smms.exception.WorkflowException;
import com.smms.reports.ReportModels.*;
import com.smms.reports.ReportService.Request;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReportControllerTest {

    private static final LocalDate D1 = LocalDate.of(2026, 3, 1), D2 = LocalDate.of(2026, 3, 31);
    private final ReportService service = mock(ReportService.class);
    private final ReportController controller = new ReportController(service);
    private final Request req = new Request(new ReportRange(D1, D2), new ReportScope(null, null, null));

    @Test
    void everyEndpointIsLimitedToTheMatrixRoles() {
        Set<String> expected = new TreeSet<>(Set.of("system_admin", "region_supervisor", "hospital_supervisor",
            "maintenance_manager", "electrical_manager", "operating_company_manager"));
        PreAuthorize pa = ReportController.class.getAnnotation(PreAuthorize.class);
        assertThat(pa).isNotNull();
        Set<String> roles = new TreeSet<>();
        Matcher m = Pattern.compile("'([a-z_]+)'").matcher(pa.value());
        while (m.find()) roles.add(m.group(1));
        assertThat(roles).isEqualTo(expected);
        for (Method method : ReportController.class.getDeclaredMethods()) {
            assertThat(method.getAnnotation(PreAuthorize.class)).as(method.getName() + " must not widen the class rule").isNull();
        }
    }

    @Test
    void volumeCsvHasBomHeaderQuotedRowsAndNeutralisesFormulas() {
        when(service.resolve(any(), any(), any(), any(), any())).thenReturn(req);
        when(service.volume(any(), any())).thenReturn(new Volume(D1, D2, "controlRoom",
            List.of(new Group("id-1", "=HYPERLINK(\"http://x\")", 3), new Group("id-2", "Room \"A\"", 2)), 5));
        ResponseEntity<?> r = controller.volume(D1, D2, null, null, "controlRoom", "csv", mock(User.class));
        assertThat(r.getHeaders().getContentType().toString()).startsWith("text/csv").containsIgnoringCase("utf-8");
        assertThat(r.getHeaders().getFirst("Content-Disposition")).isEqualTo("attachment; filename=\"work-order-volume-2026-03-01-2026-03-31.csv\"");
        String body = new String((byte[]) r.getBody(), StandardCharsets.UTF_8);
        assertThat(body).startsWith("﻿\"controlRoom\",\"label\",\"count\"\r\n");
        assertThat(body).contains("\"id-1\",\"'=HYPERLINK(\"\"http://x\"\")\",\"3\"")
            .doesNotContain(",\"=HYPERLINK");
        assertThat(body).contains("\"Room \"\"A\"\"\"");
    }

    @Test
    void jsonIsDefaultAndInvalidFormatIs400() {
        when(service.resolve(any(), any(), any(), any(), any())).thenReturn(req);
        Volume v = new Volume(D1, D2, "status", List.of(), 0);
        when(service.volume(any(), any())).thenReturn(v);
        assertThat(controller.volume(D1, D2, null, null, null, "json", mock(User.class)).getBody()).isSameAs(v);
        assertThatThrownBy(() -> controller.volume(D1, D2, null, null, null, "xml", mock(User.class)))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void csvAboveTheRowCapIs422() {
        List<TechnicianRow> many = new ArrayList<>();
        UserRef u = new UserRef(java.util.UUID.randomUUID(), "Tech");
        for (int i = 0; i < 50_001; i++) many.add(new TechnicianRow(u, 1, 1, 0, 0, BigDecimal.ONE, new ByPriority(0, 0, 1, 0), 0));
        when(service.resolve(any(), any(), any(), any(), any())).thenReturn(req);
        when(service.technicians(any(), anyInt(), anyInt(), anyBoolean())).thenReturn(new Page<>(many, 0, many.size(), many.size(), 1));
        assertThatThrownBy(() -> controller.technicians(D1, D2, null, null, 0, 20, "csv", mock(User.class)))
            .isInstanceOf(WorkflowException.class).hasMessageContaining("narrow");
    }

    @Test
    void technicianCsvContainsNoContactData() {
        when(service.resolve(any(), any(), any(), any(), any())).thenReturn(req);
        UserRef u = new UserRef(java.util.UUID.randomUUID(), "+Mallory");
        when(service.technicians(any(), anyInt(), anyInt(), anyBoolean())).thenReturn(new Page<>(
            List.of(new TechnicianRow(u, 4, 3, 1, 0, new BigDecimal("2.50"), new ByPriority(1, 1, 1, 0), 1)), 0, 1, 1, 1));
        String body = new String((byte[]) controller.technicians(D1, D2, null, null, 0, 20, "csv", mock(User.class)).getBody(),
            StandardCharsets.UTF_8);
        assertThat(body).contains("\"'+Mallory\",\"4\"").doesNotContain("@");
        assertThat(body.lines().findFirst().orElse("")).doesNotContain("email").doesNotContain("phone");
    }
}
