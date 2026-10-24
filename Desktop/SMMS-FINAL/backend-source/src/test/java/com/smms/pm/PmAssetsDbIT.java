package com.smms.pm;

import com.smms.assets.*;
import com.smms.dto.request.CreateAssetRequest;
import com.smms.dto.response.AssetResponse;
import com.smms.entity.User;
import com.smms.enums.AssetStatus;
import com.smms.exception.ConflictException;
import com.smms.exception.ResourceNotFoundException;
import com.smms.it.AbstractDbIT;
import com.smms.it.DbFixtures;
import com.smms.pm.PlanDtos.PlanResponse;
import com.smms.reports.assets.AssetReportService;
import com.smms.repository.UserRepository;
import com.smms.util.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Assets and preventive maintenance against a real PostgreSQL (V40-V44, generation, scoping, reports). */
class PmAssetsDbIT extends AbstractDbIT {

    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager txManager;
    @Autowired UserRepository users;
    @Autowired PmGenerationService generation;
    @Autowired MaintenancePlanService plans;
    @Autowired MaintenancePlanRunRepository runRepo;
    @Autowired AssetService assetService;
    @Autowired AssetHistoryService history;
    @Autowired DowntimeService downtime;
    @Autowired AssetReportService reports;

    private final LocalDate today = LocalDate.now(java.time.ZoneId.of("Asia/Riyadh"));

    @AfterEach
    void cleanup() {
        TenantContext.clear();
        // keep plans of finished tests out of later job passes
        jdbc.update("UPDATE maintenance_plans SET is_active = false");
    }

    private User user(UUID id) { return users.findById(id).orElseThrow(); }

    private UUID plan(DbFixtures.Hierarchy h, UUID asset, UUID owner, LocalDate due, UUID controlRoom) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO maintenance_plans (id, tenant_id, center_id, asset_id, control_room_id, sub_type, priority, "
            + "title, frequency_unit, frequency_interval, lead_days, start_date, next_due_date, is_active, owner_id) "
            + "VALUES (?, ?, ?, ?, ?, 'hvac'::sub_type, 'medium'::ticket_priority, 'Plan', 'month', 1, 0, ?, ?, true, ?)",
            id, h.tenant(), h.center(), asset, controlRoom, due, due, owner);
        return id;
    }

    private long count(String sql, Object... args) { return jdbc.queryForObject(sql, Long.class, args); }

    // ── schema ───────────────────────────────────────────────

    @Test
    void migrationsV40ToV44AppliedAndConstraintsHold() {
        assertThat(jdbc.queryForList("SELECT version::int FROM flyway_schema_history WHERE success AND version::int BETWEEN 40 AND 44 ORDER BY 1",
            Integer.class)).containsExactly(40, 41, 42, 43, 44);

        DbFixtures f = new DbFixtures(jdbc);
        DbFixtures.Hierarchy h = f.hierarchy();
        DbFixtures.Hierarchy other = f.hierarchy();
        UUID owner = f.user(h, "maintenance_manager");
        UUID asset = f.asset(h);
        UUID otherAsset = f.asset(other);
        UUID plan = plan(h, asset, owner, today, h.controlRoom());

        // duplicate (plan, due date) is refused by the database
        jdbc.update("INSERT INTO maintenance_plan_runs (id, tenant_id, plan_id, due_date, status) VALUES (?, ?, ?, ?, 'failed')",
            UUID.randomUUID(), h.tenant(), plan, today);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO maintenance_plan_runs (id, tenant_id, plan_id, due_date, status) VALUES (?, ?, ?, ?, 'generated')",
            UUID.randomUUID(), h.tenant(), plan, today)).isInstanceOf(DataIntegrityViolationException.class);
        // a plan must reference an asset of its own tenant (composite FK)
        assertThatThrownBy(() -> plan(h, otherAsset, owner, today, h.controlRoom())).isInstanceOf(DataIntegrityViolationException.class);
        // plan checks
        assertThatThrownBy(() -> jdbc.update("UPDATE maintenance_plans SET frequency_interval = 0 WHERE id = ?", plan))
            .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE maintenance_plans SET lead_days = 61 WHERE id = ?", plan))
            .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE maintenance_plans SET frequency_unit = 'hour' WHERE id = ?", plan))
            .isInstanceOf(DataIntegrityViolationException.class);

        // asset code unique per center among live rows; NULL codes repeat; soft delete frees the code
        UUID a1 = f.asset(h), a2 = f.asset(h), a3 = f.asset(h);
        jdbc.update("UPDATE assets SET asset_code = 'X-1' WHERE id = ?", a1);
        assertThatThrownBy(() -> jdbc.update("UPDATE assets SET asset_code = 'X-1' WHERE id = ?", a2))
            .isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("UPDATE assets SET deleted_at = now() WHERE id = ?", a1);
        jdbc.update("UPDATE assets SET asset_code = 'X-1' WHERE id = ?", a2);
        assertThat(count("SELECT count(*) FROM assets WHERE id = ? AND asset_code IS NULL", a3)).isEqualTo(1);
        assertThatThrownBy(() -> jdbc.update("UPDATE assets SET criticality = 'urgent' WHERE id = ?", a3))
            .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE assets SET decommissioned_at = now() WHERE id = ?", a3))
            .isInstanceOf(DataIntegrityViolationException.class); // reason missing
        assertThatThrownBy(() -> jdbc.update("UPDATE assets SET install_date = '2025-02-01', warranty_expiry = '2025-01-01' WHERE id = ?", a3))
            .isInstanceOf(DataIntegrityViolationException.class);

        // one open downtime per asset; ended >= started
        UUID u = owner;
        jdbc.update("INSERT INTO asset_downtime (id, tenant_id, asset_id, started_at, reason, recorded_by_id) VALUES (?, ?, ?, now(), 'r', ?)",
            UUID.randomUUID(), h.tenant(), asset, u);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO asset_downtime (id, tenant_id, asset_id, started_at, reason, recorded_by_id) VALUES (?, ?, ?, now(), 'r', ?)",
            UUID.randomUUID(), h.tenant(), asset, u)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO asset_downtime (id, tenant_id, asset_id, started_at, ended_at, reason, recorded_by_id) "
            + "VALUES (?, ?, ?, now(), now() - interval '1 hour', 'r', ?)", UUID.randomUUID(), h.tenant(), a3, u))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ── generation ───────────────────────────────────────────

    @Test
    void concurrentGenerationCreatesExactlyOneWorkOrder() throws Exception {
        DbFixtures f = new DbFixtures(jdbc);
        DbFixtures.Hierarchy h = f.hierarchy();
        UUID owner = f.user(h, "maintenance_manager");
        UUID asset = f.asset(h);
        UUID plan = plan(h, asset, owner, today, h.controlRoom());

        int threads = 4;
        CyclicBarrier barrier = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<PmGenerationService.Result>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            results.add(pool.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return generation.generate(plan, h.tenant(), today, false);
            }));
        }
        int generated = 0;
        for (Future<PmGenerationService.Result> r : results) {
            if (r.get(60, TimeUnit.SECONDS).outcome() == PmGenerationService.Outcome.GENERATED) generated++;
        }
        pool.shutdownNow();

        assertThat(generated).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM work_orders WHERE asset_id = ?", asset)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM maintenance_plan_runs WHERE plan_id = ?", plan)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT next_due_date FROM maintenance_plans WHERE id = ?", java.sql.Date.class, plan).toLocalDate())
            .isEqualTo(today.plusMonths(1));
        // the generated order is a normal new ticket with the owner as requester and an audit row
        Map<String, Object> wo = jdbc.queryForMap("SELECT status::text AS s, requested_by_id, tenant_id, ticket_number FROM work_orders WHERE asset_id = ?", asset);
        assertThat(wo.get("s")).isEqualTo("new");
        assertThat(wo.get("requested_by_id")).isEqualTo(owner);
        assertThat(wo.get("tenant_id")).isEqualTo(h.tenant());
        assertThat(wo.get("ticket_number")).isNotNull();
        assertThat(count("SELECT count(*) FROM ticket_history th JOIN work_orders w ON w.id = th.work_order_id WHERE w.asset_id = ?", asset)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM maintenance_plan_runs WHERE plan_id = ? AND work_order_id IS NOT NULL AND status = 'generated'", plan)).isEqualTo(1);
    }

    @Test
    void uniqueConstraintAloneStopsRacingClaims() throws Exception {
        DbFixtures f = new DbFixtures(jdbc);
        DbFixtures.Hierarchy h = f.hierarchy();
        UUID plan = plan(h, f.asset(h), f.user(h, "maintenance_manager"), today, h.controlRoom());
        int threads = 6;
        CyclicBarrier barrier = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            results.add(pool.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return new TransactionTemplate(txManager).execute(s -> runRepo.claim(UUID.randomUUID(), h.tenant(), plan, today, "scheduler"));
            }));
        }
        int claimed = 0;
        for (Future<Integer> r : results) claimed += r.get(30, TimeUnit.SECONDS);
        pool.shutdownNow();
        assertThat(claimed).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM maintenance_plan_runs WHERE plan_id = ?", plan)).isEqualTo(1);
    }

    @Test
    void twoJobPassesGenerateOnceAndTheNextPeriodOnlyWhenDue() {
        DbFixtures f = new DbFixtures(jdbc);
        DbFixtures.Hierarchy h = f.hierarchy();
        UUID asset = f.asset(h);
        UUID plan = plan(h, asset, f.user(h, "maintenance_manager"), today, h.controlRoom());

        generation.runDue(today);
        generation.runDue(today);

        assertThat(count("SELECT count(*) FROM work_orders WHERE asset_id = ?", asset)).isEqualTo(1);
        // a later day generates the next period (one run per pass, backlog stays visible)
        generation.generate(plan, h.tenant(), today.plusMonths(1), false);
        assertThat(count("SELECT count(*) FROM work_orders WHERE asset_id = ?", asset)).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM maintenance_plan_runs WHERE plan_id = ?", plan)).isEqualTo(2);
    }

    @Test
    void catchUpGeneratesOneRunPerPassAfterAnOutage() {
        DbFixtures f = new DbFixtures(jdbc);
        DbFixtures.Hierarchy h = f.hierarchy();
        UUID asset = f.asset(h);
        UUID plan = plan(h, asset, f.user(h, "maintenance_manager"), today.minusMonths(2), h.controlRoom());
        generation.generate(plan, h.tenant(), today, false);
        assertThat(count("SELECT count(*) FROM work_orders WHERE asset_id = ?", asset)).isEqualTo(1);
        generation.generate(plan, h.tenant(), today, false);
        assertThat(count("SELECT count(*) FROM work_orders WHERE asset_id = ?", asset)).isEqualTo(2);
    }

    @Test
    void crossTenantReferencesFailTheRunWithoutCreatingAnyWorkOrderAndNotifyOnce() {
        DbFixtures f = new DbFixtures(jdbc);
        DbFixtures.Hierarchy a = f.hierarchy();
        DbFixtures.Hierarchy b = f.hierarchy();
        UUID mmA = f.user(a, "maintenance_manager");
        UUID mmB = f.user(b, "maintenance_manager");
        UUID asset = f.asset(a);
        // tenant A's plan points at tenant B's control room
        UUID plan = plan(a, asset, mmA, today, b.controlRoom());

        PmGenerationService.Result first = generation.generate(plan, a.tenant(), today, false);
        PmGenerationService.Result second = generation.generate(plan, a.tenant(), today, false);

        assertThat(first.outcome()).isEqualTo(PmGenerationService.Outcome.FAILED);
        assertThat(second.outcome()).isEqualTo(PmGenerationService.Outcome.FAILED);
        assertThat(count("SELECT count(*) FROM work_orders WHERE asset_id = ? OR tenant_id IN (?, ?)", asset, a.tenant(), b.tenant())).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM maintenance_plan_runs WHERE plan_id = ?", String.class, plan)).isEqualTo("failed");
        assertThat(count("SELECT count(*) FROM notifications WHERE user_id = ? AND type = 'PM_FAILED'", mmA)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM notifications WHERE user_id = ?", mmB)).isZero();
        assertThat(jdbc.queryForObject("SELECT next_due_date FROM maintenance_plans WHERE id = ?", java.sql.Date.class, plan).toLocalDate())
            .isEqualTo(today); // stays due
    }

    @Test
    void failedRunIsRetriedAfterTheCauseIsFixed() {
        DbFixtures f = new DbFixtures(jdbc);
        DbFixtures.Hierarchy a = f.hierarchy();
        DbFixtures.Hierarchy b = f.hierarchy();
        UUID owner = f.user(a, "maintenance_manager");
        UUID asset = f.asset(a);
        UUID plan = plan(a, asset, owner, today, b.controlRoom());
        assertThat(generation.generate(plan, a.tenant(), today, false).outcome()).isEqualTo(PmGenerationService.Outcome.FAILED);

        jdbc.update("UPDATE maintenance_plans SET control_room_id = ? WHERE id = ?", a.controlRoom(), plan);
        assertThat(generation.generate(plan, a.tenant(), today, false).outcome()).isEqualTo(PmGenerationService.Outcome.GENERATED);
        assertThat(jdbc.queryForObject("SELECT status FROM maintenance_plan_runs WHERE plan_id = ?", String.class, plan)).isEqualTo("generated");
        assertThat(count("SELECT count(*) FROM work_orders WHERE asset_id = ?", asset)).isEqualTo(1);
    }

    @Test
    void overdueRunsAreNotifiedOnceAndOnlyWhileOpen() {
        DbFixtures f = new DbFixtures(jdbc);
        DbFixtures.Hierarchy h = f.hierarchy();
        UUID mm = f.user(h, "maintenance_manager");
        UUID asset = f.asset(h);
        UUID plan = plan(h, asset, mm, today.plusMonths(3), h.controlRoom());
        UUID openWo = f.workOrder(h, mm, "in_progress", "medium");
        UUID doneWo = f.workOrder(h, mm, "completed", "medium");
        jdbc.update("INSERT INTO maintenance_plan_runs (id, tenant_id, plan_id, due_date, status, work_order_id) VALUES (?, ?, ?, ?, 'generated', ?)",
            UUID.randomUUID(), h.tenant(), plan, today.minusDays(3), openWo);
        jdbc.update("INSERT INTO maintenance_plan_runs (id, tenant_id, plan_id, due_date, status, work_order_id) VALUES (?, ?, ?, ?, 'generated', ?)",
            UUID.randomUUID(), h.tenant(), plan, today.minusDays(5), doneWo);

        generation.notifyOverdue(today);
        generation.notifyOverdue(today);

        // owner and center manager are the same user: exactly one notification, for the open order only
        assertThat(count("SELECT count(*) FROM notifications WHERE user_id = ? AND type = 'PM_OVERDUE' AND work_order_id = ?", mm, openWo)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM notifications WHERE type = 'PM_OVERDUE' AND work_order_id = ?", doneWo)).isZero();
        assertThat(count("SELECT count(*) FROM maintenance_plan_runs WHERE plan_id = ? AND overdue_notified_at IS NOT NULL", plan)).isEqualTo(1);
    }

    // ── services with scope ──────────────────────────────────

    @Test
    void planListingAndOverdueFilterAreTenantAndCenterScoped() {
        DbFixtures f = new DbFixtures(jdbc);
        DbFixtures.Hierarchy a = f.hierarchy();
        DbFixtures.Hierarchy b = f.hierarchy();
        UUID mmA = f.user(a, "maintenance_manager");
        UUID mmB = f.user(b, "maintenance_manager");
        UUID late = plan(a, f.asset(a), mmA, today.minusDays(4), a.controlRoom());
        UUID fine = plan(a, f.asset(a), mmA, today.plusDays(9), a.controlRoom());
        UUID foreign = plan(b, f.asset(b), mmB, today.minusDays(4), b.controlRoom());

        TenantContext.set(a.tenant());
        User actor = user(mmA);
        List<UUID> all = plans.list(null, null, null, false, 0, 50, null, actor).map(PlanResponse::id).getContent();
        assertThat(all).containsExactlyInAnyOrder(late, fine);
        List<UUID> overdue = plans.list(null, null, true, true, 0, 50, null, actor).map(PlanResponse::id).getContent();
        assertThat(overdue).containsExactly(late);
        assertThatThrownBy(() -> plans.get(foreign, actor)).isInstanceOf(ResourceNotFoundException.class);
        // a role without plan access sees nothing
        assertThat(plans.list(null, null, null, false, 0, 50, null, user(f.user(a, "technician"))).getContent()).isEmpty();
        // other center of the same tenant is invisible to a center-bound reader
        DbFixtures.Hierarchy sameTenantOtherCenter = otherCenterOfTenant(f, a);
        UUID hidden = plan(sameTenantOtherCenter, f.asset(sameTenantOtherCenter), mmA, today, sameTenantOtherCenter.controlRoom());
        assertThatThrownBy(() -> plans.get(hidden, actor)).isInstanceOf(ResourceNotFoundException.class);
        assertThat(plans.list(null, null, null, false, 0, 50, null, actor).getContent()).hasSize(2);
    }

    private DbFixtures.Hierarchy otherCenterOfTenant(DbFixtures f, DbFixtures.Hierarchy a) {
        UUID center = UUID.randomUUID(), room = UUID.randomUUID();
        jdbc.update("INSERT INTO centers (id, tenant_id, region_id, name, name_ar, code) VALUES (?, ?, ?, 'C2', 'C2', ?)",
            center, a.tenant(), a.region(), "C" + center.toString().substring(0, 8));
        jdbc.update("INSERT INTO control_rooms (id, tenant_id, center_id, type, name, name_ar) VALUES (?, ?, ?, 'hospital'::control_room_type, 'CR2', 'CR2')",
            room, a.tenant(), center);
        return new DbFixtures.Hierarchy(a.tenant(), a.region(), center, room);
    }

    @Test
    void createPlanThroughTheServiceAndGenerateNowIs409Twice() {
        DbFixtures f = new DbFixtures(jdbc);
        DbFixtures.Hierarchy h = f.hierarchy();
        UUID mm = f.user(h, "maintenance_manager");
        UUID asset = f.asset(h);
        TenantContext.set(h.tenant());
        User actor = user(mm);

        PlanResponse created = plans.create(new PlanDtos.CreatePlanRequest(asset, h.controlRoom(),
            com.smms.enums.SubType.electrical, null, "Monthly check", "steps", FrequencyUnit.month, 1, 2, today.plusDays(10), null), actor);
        assertThat(created.nextDueDate()).isEqualTo(today.plusDays(10));
        assertThat(created.ownerId()).isEqualTo(mm);

        PlanDtos.GenerateResponse g = plans.generateNow(created.id(), actor);
        assertThat(g.workOrderId()).isNotNull();
        assertThat(g.nextDueDate()).isEqualTo(today.plusDays(10).plusMonths(1));
        assertThat(count("SELECT count(*) FROM work_orders WHERE asset_id = ?", asset)).isEqualTo(1);

        // pointing the plan back at the generated period must not produce a second order
        jdbc.update("UPDATE maintenance_plans SET next_due_date = ?, version = version + 1 WHERE id = ?", today.plusDays(10), created.id());
        assertThatThrownBy(() -> plans.generateNow(created.id(), actor)).isInstanceOf(ConflictException.class);
        assertThat(count("SELECT count(*) FROM work_orders WHERE asset_id = ?", asset)).isEqualTo(1);
        assertThat(plans.get(created.id(), actor).lastRuns()).hasSize(1);
    }

    @Test
    void assetRegisterCrudScopeAndDecommission() {
        DbFixtures f = new DbFixtures(jdbc);
        DbFixtures.Hierarchy a = f.hierarchy();
        DbFixtures.Hierarchy b = f.hierarchy();
        UUID mmA = f.user(a, "maintenance_manager");
        UUID mmB = f.user(b, "maintenance_manager");
        UUID teA = f.user(a, "technician");

        TenantContext.set(a.tenant());
        User actor = user(mmA);
        CreateAssetRequest req = new CreateAssetRequest();
        req.setCenterId(a.center());
        req.setName("Ventilator");
        req.setNameAr("x");
        req.setAssetCode("vent-1");
        req.setCategory("Medical");
        req.setCriticality(AssetCriticality.critical);
        AssetResponse created = assetService.create(req, actor);
        assertThat(created.getAssetCode()).isEqualTo("VENT-1");
        assertThatThrownBy(() -> assetService.create(req, actor)).isInstanceOf(ConflictException.class);

        // listing: scoped, searchable, open work orders counted only when readable
        UUID wo = f.workOrder(a, mmA, "in_progress", "high");
        jdbc.update("UPDATE work_orders SET asset_id = ?, assigned_to_id = ? WHERE id = ?", created.getId(), teA, wo);
        UUID hiddenWo = f.workOrder(a, mmA, "new", "low");
        jdbc.update("UPDATE work_orders SET asset_id = ? WHERE id = ?", created.getId(), hiddenWo);
        AssetResponse mmView = assetService.list(null, null, null, null, "vent", false, 0, 20, null, actor).getContent().get(0);
        assertThat(mmView.getOpenWorkOrders()).isEqualTo(2);
        User te = user(teA);
        AssetResponse teView = assetService.list(null, null, null, null, null, false, 0, 20, null, te).getContent().get(0);
        assertThat(teView.getOpenWorkOrders()).isEqualTo(1); // only the order assigned to the technician

        // cross-tenant ids are 404
        TenantContext.set(b.tenant());
        User other = user(mmB);
        assertThatThrownBy(() -> assetService.get(created.getId(), other)).isInstanceOf(ResourceNotFoundException.class);
        assertThat(assetService.list(null, null, null, null, null, true, 0, 20, null, other).getContent()).isEmpty();

        // decommission: blocked by open orders, then allowed; plans paused; hidden by default
        TenantContext.set(a.tenant());
        UUID plan = plan(a, created.getId(), mmA, today, a.controlRoom());
        assertThatThrownBy(() -> assetService.decommission(created.getId(), "retired", actor)).isInstanceOf(ConflictException.class);
        jdbc.update("UPDATE work_orders SET status = 'cancelled' WHERE id IN (?, ?)", wo, hiddenWo);
        AssetResponse done = assetService.decommission(created.getId(), "retired", actor);
        assertThat(done.getDecommissionedAt()).isNotNull();
        assertThat(jdbc.queryForObject("SELECT is_active FROM maintenance_plans WHERE id = ?", Boolean.class, plan)).isFalse();
        assertThat(assetService.list(null, null, null, null, null, false, 0, 20, null, actor).getContent()).isEmpty();
        assertThat(assetService.list(null, null, null, null, null, true, 0, 20, null, actor).getContent()).hasSize(1);
    }

    @Test
    void assetHistoryOnlyReturnsReadableOrdersAndTheirMaterials() {
        DbFixtures f = new DbFixtures(jdbc);
        DbFixtures.Hierarchy h = f.hierarchy();
        UUID mm = f.user(h, "maintenance_manager");
        UUID te = f.user(h, "technician");
        UUID asset = f.asset(h);
        UUID mine = f.workOrder(h, mm, "completed", "medium");
        UUID notMine = f.workOrder(h, mm, "in_progress", "medium");
        jdbc.update("UPDATE work_orders SET asset_id = ?, assigned_to_id = ?, tech_report = 'replaced belt', completed_date = now() WHERE id = ?", asset, te, mine);
        jdbc.update("UPDATE work_orders SET asset_id = ? WHERE id = ?", asset, notMine);
        UUID item = UUID.randomUUID();
        jdbc.update("INSERT INTO inventory_items (id, tenant_id, center_id, name, name_ar, sku) VALUES (?, ?, ?, 'Belt', 'Belt', ?)",
            item, h.tenant(), h.center(), "SKU-" + item.toString().substring(0, 6));
        for (UUID wo : List.of(mine, notMine)) {
            jdbc.update("INSERT INTO inventory_movements (id, tenant_id, item_id, work_order_id, type, quantity) "
                + "VALUES (?, ?, ?, ?, 'stock_out'::inventory_movement_type, 2)", UUID.randomUUID(), h.tenant(), item, wo);
        }
        TenantContext.set(h.tenant());

        var mmRows = history.workOrders(asset, 0, 20, user(mm)).getContent();
        assertThat(mmRows).extracting(AssetHistoryService.AssetWorkOrderRow::id).containsExactlyInAnyOrder(mine, notMine);
        var teRows = history.workOrders(asset, 0, 20, user(te)).getContent();
        assertThat(teRows).extracting(AssetHistoryService.AssetWorkOrderRow::id).containsExactly(mine);
        assertThat(teRows.get(0).techReport()).isEqualTo("replaced belt");

        assertThat(history.materials(asset, null, null, user(mm))).hasSize(2)
            .allSatisfy(m -> assertThat(m.kind()).isEqualTo("issue"));
        assertThat(history.materials(asset, null, null, user(te))).extracting(AssetHistoryService.MaterialRow::workOrderId).containsExactly(mine);
        assertThat(history.materials(asset, Instant.now().plusSeconds(3600), null, user(mm))).isEmpty();
    }

    @Test
    void downtimeFlowOverlapAndStatusRestore() {
        DbFixtures f = new DbFixtures(jdbc);
        DbFixtures.Hierarchy h = f.hierarchy();
        UUID mm = f.user(h, "maintenance_manager");
        UUID asset = f.asset(h);
        TenantContext.set(h.tenant());
        User actor = user(mm);

        Instant start = Instant.now().minusSeconds(7200);
        AssetDowntime d = downtime.start(asset, new DowntimeService.StartRequest("compressor", null, start), actor);
        assertThat(jdbc.queryForObject("SELECT status::text FROM assets WHERE id = ?", String.class, asset)).isEqualTo("under_maintenance");
        assertThatThrownBy(() -> downtime.start(asset, new DowntimeService.StartRequest("again", null, null), actor))
            .isInstanceOf(ConflictException.class);

        downtime.end(asset, d.getId(), new DowntimeService.EndRequest(start.plusSeconds(3600)), actor);
        assertThat(jdbc.queryForObject("SELECT status::text FROM assets WHERE id = ?", String.class, asset)).isEqualTo("active");
        // a back-dated period overlapping the closed one is refused
        assertThatThrownBy(() -> downtime.start(asset, new DowntimeService.StartRequest("overlap", null, start.plusSeconds(60)), actor))
            .isInstanceOf(ConflictException.class);
        assertThat(downtime.list(asset, 0, 10, actor).getContent()).hasSize(1);
        assertThat(assetService.get(asset, actor).isActiveDowntime()).isFalse();
    }

    // ── reports with known answers ───────────────────────────

    @Test
    void pmComplianceAndAssetSummaryKnownAnswers() {
        DbFixtures f = new DbFixtures(jdbc);
        DbFixtures.Hierarchy h = f.hierarchy();
        UUID mm = f.user(h, "maintenance_manager");
        UUID asset = f.asset(h);
        UUID asset2 = f.asset(h);
        jdbc.update("UPDATE assets SET category = 'HVAC', criticality = 'high' WHERE id = ?", asset);
        UUID plan = plan(h, asset, mm, LocalDate.parse("2030-01-01"), h.controlRoom());
        UUID onTime = f.workOrder(h, mm, "completed", "medium");
        UUID late = f.workOrder(h, mm, "completed", "medium");
        UUID open = f.workOrder(h, mm, "in_progress", "medium");
        jdbc.update("UPDATE work_orders SET created_at = '2025-01-01T00:00:00Z', completed_date = '2025-01-05T10:00:00Z' WHERE id = ?", onTime);
        jdbc.update("UPDATE work_orders SET created_at = '2025-01-01T00:00:00Z', completed_date = '2025-01-12T10:00:00Z' WHERE id = ?", late);
        insertRun(h, plan, "2025-01-05", "generated", onTime);
        insertRun(h, plan, "2025-01-10", "generated", late);
        insertRun(h, plan, "2025-01-15", "generated", open);
        insertRun(h, plan, "2025-01-20", "failed", null);

        TenantContext.set(h.tenant());
        User actor = user(mm);
        var c = reports.compliance(LocalDate.parse("2025-01-01"), LocalDate.parse("2025-01-31"), null, actor);
        assertThat(c.due()).isEqualTo(4);
        assertThat(c.generated()).isEqualTo(3);
        assertThat(c.failed()).isEqualTo(1);
        assertThat(c.completedOnTime()).isEqualTo(1);
        assertThat(c.completedLate()).isEqualTo(1);
        assertThat(c.overdue()).isEqualTo(1);
        assertThat(c.rows()).hasSize(4);
        assertThat(reports.compliance(LocalDate.parse("2025-02-01"), LocalDate.parse("2025-02-28"), null, actor).due()).isZero();

        // downtime: 12 h inside the range
        jdbc.update("INSERT INTO asset_downtime (id, tenant_id, asset_id, started_at, ended_at, reason, recorded_by_id) "
            + "VALUES (?, ?, ?, '2025-01-10T00:00:00Z', '2025-01-10T12:00:00Z', 'r', ?)", UUID.randomUUID(), h.tenant(), asset, mm);
        var s = reports.summary(LocalDate.parse("2025-01-01"), LocalDate.parse("2025-01-31"), null, actor);
        assertThat(s.totalAssets()).isEqualTo(2);
        assertThat(s.byCategory()).extracting(AssetReportService.Count::key).contains("HVAC", "");
        assertThat(s.downtimeHours()).isEqualTo(12.0);
        assertThat(s.downtimeByAsset()).hasSize(1);
        assertThat(asset2).isNotNull();

        // another tenant's manager sees none of it
        DbFixtures.Hierarchy other = f.hierarchy();
        TenantContext.set(other.tenant());
        User otherMm = user(f.user(other, "maintenance_manager"));
        assertThat(reports.compliance(LocalDate.parse("2025-01-01"), LocalDate.parse("2025-01-31"), null, otherMm).due()).isZero();
        assertThat(reports.summary(LocalDate.parse("2025-01-01"), LocalDate.parse("2025-01-31"), null, otherMm).totalAssets()).isZero();
    }

    private void insertRun(DbFixtures.Hierarchy h, UUID plan, String due, String status, UUID wo) {
        jdbc.update("INSERT INTO maintenance_plan_runs (id, tenant_id, plan_id, due_date, status, work_order_id) VALUES (?, ?, ?, ?::date, ?, ?)",
            UUID.randomUUID(), h.tenant(), plan, due, status, wo);
    }
}
