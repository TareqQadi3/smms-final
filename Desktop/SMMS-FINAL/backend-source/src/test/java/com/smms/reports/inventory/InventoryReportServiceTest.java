package com.smms.reports.inventory;

import com.smms.entity.Center;
import com.smms.entity.Region;
import com.smms.entity.User;
import com.smms.enums.UserRole;
import com.smms.exception.ResourceNotFoundException;
import com.smms.exception.WorkflowException;
import com.smms.reports.inventory.InventoryReportService.Report;
import com.smms.repository.CenterRepository;
import com.smms.support.Fixtures;
import com.smms.util.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.access.AccessDeniedException;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class InventoryReportServiceTest {

    private final UUID tenant = UUID.randomUUID();
    private final Region region = Fixtures.region(tenant);
    private final Center centerA = Fixtures.center(region, tenant);
    private final Center centerB = Fixtures.center(region, tenant);
    private final Center foreign = Fixtures.center(Fixtures.region(tenant), tenant);
    private NamedParameterJdbcTemplate jdbc;
    private CenterRepository centers;
    private InventoryReportService service;

    @BeforeEach
    void setUp() {
        jdbc = mock(NamedParameterJdbcTemplate.class);
        centers = mock(CenterRepository.class);
        for (Center c : List.of(centerA, centerB, foreign)) when(centers.findById(c.getId())).thenReturn(Optional.of(c));
        service = new InventoryReportService(jdbc, centers);
        TenantContext.set(tenant);
    }

    @AfterEach
    void clear() { TenantContext.clear(); }

    private User user(UserRole r, Center c) { return Fixtures.user(r, c, c == null ? region : null, tenant); }

    @Test
    void scopeClauseFollowsTheRole() {
        MapSqlParameterSource p = new MapSqlParameterSource();
        String hs = service.scope(user(UserRole.hospital_supervisor, centerA), null, p, "x.tenant_id").toString();
        assertThat(hs).contains("x.tenant_id = :tenant").contains("c.id = :scopeCenter");
        assertThat(p.getValue("scopeCenter")).isEqualTo(centerA.getId());
        assertThat(p.getValue("tenant")).isEqualTo(tenant);

        p = new MapSqlParameterSource();
        String rs = service.scope(user(UserRole.region_supervisor, null), null, p, "x.tenant_id").toString();
        assertThat(rs).contains("c.region_id = :region");
        assertThat(p.getValue("region")).isEqualTo(region.getId());

        String sa = service.scope(user(UserRole.system_admin, null), null, new MapSqlParameterSource(), "x.tenant_id").toString();
        assertThat(sa).doesNotContain("region").doesNotContain("scopeCenter");

        String none = service.scope(user(UserRole.resident, centerA), null, new MapSqlParameterSource(), "x.tenant_id").toString();
        assertThat(none).contains("1 = 0");
    }

    @Test
    void platformAdminHasNoTenantPredicate() {
        TenantContext.clear();
        String sql = service.scope(user(UserRole.system_admin, null), null, new MapSqlParameterSource(), "x.tenant_id").toString();
        assertThat(sql).doesNotContain("tenant");
    }

    @Test
    void centerFilterMustBeInsideTheScope() {
        MapSqlParameterSource p = new MapSqlParameterSource();
        User hs = user(UserRole.hospital_supervisor, centerA);
        assertThat(service.scope(hs, centerA.getId(), p, "t").toString()).contains("c.id = :filterCenter");
        assertThatThrownBy(() -> service.scope(hs, centerB.getId(), new MapSqlParameterSource(), "t"))
            .isInstanceOf(ResourceNotFoundException.class);
        User rs = user(UserRole.region_supervisor, null);
        service.scope(rs, centerB.getId(), new MapSqlParameterSource(), "t");
        assertThatThrownBy(() -> service.scope(rs, foreign.getId(), new MapSqlParameterSource(), "t"))
            .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.scope(rs, UUID.randomUUID(), new MapSqlParameterSource(), "t"))
            .isInstanceOf(ResourceNotFoundException.class);
        TenantContext.set(UUID.randomUUID()); // another tenant's center id
        assertThatThrownBy(() -> service.scope(rs, centerA.getId(), new MapSqlParameterSource(), "t"))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void roleGates() {
        User wh = user(UserRole.warehouse, centerA);
        assertThatThrownBy(() -> service.purchaseOrders(wh, null, null, null, null)).isInstanceOf(AccessDeniedException.class);
        for (UserRole r : List.of(UserRole.technician, UserRole.control_room, UserRole.resident, UserRole.department, UserRole.other)) {
            User u = user(r, centerA);
            assertThatThrownBy(() -> service.stock(u, null, null)).as(r.name()).isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> service.movements(u, null, null, null, null, null, null)).as(r.name()).isInstanceOf(AccessDeniedException.class);
        }
    }

    @Test
    void dateRangeAndGroupByAreValidatedBeforeAnyQuery() {
        User mm = user(UserRole.maintenance_manager, centerA);
        LocalDate d = LocalDate.of(2026, 3, 1);
        assertThatThrownBy(() -> service.movements(mm, d.plusDays(1), d, null, null, null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.movements(mm, d, null, null, null, null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.consumption(mm, d.minusDays(400), d, "item", null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.purchaseOrders(mm, d.minusDays(366), d, null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.consumption(mm, d, d, "; drop table x", null)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(jdbc);
    }

    @SuppressWarnings("unchecked")
    @Test
    void groupByChoosesTheColumnsAndSqlNeverContainsUserText() {
        User mm = user(UserRole.maintenance_manager, centerA);
        when(jdbc.query(anyString(), any(MapSqlParameterSource.class), any(ResultSetExtractor.class))).thenReturn(List.of());
        LocalDate d = LocalDate.of(2026, 3, 1);
        for (String g : List.of("workOrder", "item", "asset")) {
            service.consumption(mm, d, d, g, null);
        }
        var sql = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(jdbc, times(3)).query(sql.capture(), any(MapSqlParameterSource.class), any(ResultSetExtractor.class));
        assertThat(sql.getAllValues().get(0)).contains("GROUP BY wo.ticket_number");
        assertThat(sql.getAllValues().get(1)).contains("GROUP BY i.sku");
        assertThat(sql.getAllValues().get(2)).contains("COALESCE(a.name");
        // user-controlled kind/status/ids are bound parameters, not SQL text
        service.movements(mm, d, d, null, null, "issue' OR '1'='1", null);
        service.purchaseOrders(mm, d, d, "x' OR '1'='1", null);
        var all = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(jdbc, times(5)).query(all.capture(), any(MapSqlParameterSource.class), any(ResultSetExtractor.class));
        assertThat(all.getAllValues().get(3)).doesNotContain("OR '1'='1").contains(":kind");
        assertThat(all.getAllValues().get(4)).doesNotContain("OR '1'='1").contains(":status");
    }

    @SuppressWarnings("unchecked")
    @Test
    void moreThanTheRowCapIsA422() {
        List<List<Object>> rows = new ArrayList<>();
        for (int i = 0; i < 50_001; i++) rows.add(List.of(i));
        when(jdbc.query(anyString(), any(MapSqlParameterSource.class), any(ResultSetExtractor.class))).thenReturn(rows);
        assertThatThrownBy(() -> service.openMaterialRequests(user(UserRole.maintenance_manager, centerA), null))
            .isInstanceOf(WorkflowException.class).hasMessageContaining("narrow");
    }

    @Test
    void cellsAreConvertedToJavaTimeTypes() {
        Instant now = Instant.parse("2026-03-01T10:00:00Z");
        assertThat(InventoryReportService.cell(Timestamp.from(now))).isEqualTo(now);
        assertThat(InventoryReportService.cell(java.sql.Date.valueOf("2026-03-01"))).isEqualTo(LocalDate.of(2026, 3, 1));
        assertThat(InventoryReportService.cell("x")).isEqualTo("x");
        assertThat(InventoryReportService.cell(null)).isNull();
    }

    @Test
    void csvDownloadIsFormulaSafeAndNamedAfterTheRange() {
        InventoryReportService svc = mock(InventoryReportService.class);
        LocalDate from = LocalDate.of(2026, 3, 1), to = LocalDate.of(2026, 3, 31);
        when(svc.movements(any(), any(), any(), any(), any(), any(), any())).thenReturn(new Report("movements", from, to,
            List.of("item", "qty"), List.of(List.of("=HYPERLINK(\"http://x\")", new java.math.BigDecimal("2.50")), List.of("a,b", 1))));
        ResponseEntity<?> r = new InventoryReportController(svc).movements(null, from, to, null, null, null, null, "csv");
        String body = new String((byte[]) r.getBody(), StandardCharsets.UTF_8);
        assertThat(body).startsWith("﻿\"item\",\"qty\"\r\n");
        assertThat(body).contains("\"'=HYPERLINK(");
        assertThat(body).contains("\"a,b\"");
        assertThat(r.getHeaders().getFirst("Content-Disposition")).isEqualTo("attachment; filename=\"movements-2026-03-01-2026-03-31.csv\"");
        assertThat(r.getHeaders().getContentType().toString()).startsWith("text/csv").containsIgnoringCase("utf-8");
        assertThat(r.getHeaders().getFirst("Cache-Control")).isEqualTo("no-store");
        assertThatThrownBy(() -> new InventoryReportController(svc).movements(null, from, to, null, null, null, null, "xml"))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
