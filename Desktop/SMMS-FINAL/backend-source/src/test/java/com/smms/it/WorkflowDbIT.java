package com.smms.it;

import com.smms.department.DepartmentService;
import com.smms.dto.request.CreateDepartmentRequest;
import com.smms.entity.User;
import com.smms.enums.TicketStatus;
import com.smms.exception.ConflictException;
import com.smms.exception.ResourceNotFoundException;
import com.smms.exception.WorkflowException;
import com.smms.repository.UserRepository;
import com.smms.service.WorkOrderService;
import com.smms.util.TenantContext;
import com.smms.workflow.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Workflow program (WS1) against a real PostgreSQL: SLA pause with the V13/V20 columns, the unique
 * constraints that make duplicates impossible (open info request, escalation level, rating, active
 * status link), the escalation scheduler pass, the public status lookup and department scoping.
 */
@TestPropertySource(properties = "app.public-status.enabled=true")
class WorkflowDbIT extends AbstractDbIT {

    @Autowired JdbcTemplate jdbc;
    @Autowired UserRepository users;
    @Autowired WorkOrderService workOrders;
    @Autowired EscalationService escalation;
    @Autowired EscalationRepository escalationRepo;
    @Autowired EmergencyService emergency;
    @Autowired RequesterConfirmationService confirmation;
    @Autowired RatingService rating;
    @Autowired StatusLinkService statusLinks;
    @Autowired DepartmentService departments;

    @AfterEach
    void clear() { TenantContext.clear(); }

    private User load(UUID id) { return users.findById(id).orElseThrow(); }

    private UUID ticket(DbFixtures f, DbFixtures.Hierarchy h, UUID requester, String status, String priority) {
        return f.workOrder(h, requester, status, priority);
    }

    // ── schema ──

    @Test
    void migrationsReachedV25AndConstraintsEnforce() {
        assertThat(jdbc.queryForObject("SELECT max(version::int) FROM flyway_schema_history WHERE success", Integer.class))
            .isGreaterThanOrEqualTo(25);
        DbFixtures f = new DbFixtures(jdbc);
        DbFixtures.Hierarchy h = f.hierarchy();
        UUID req = f.user(h, "department");
        UUID wo = ticket(f, h, req, "under_review", "medium");
        // escalation level range and unique (work order, level)
        jdbc.update("INSERT INTO work_order_escalations (tenant_id, work_order_id, level, trigger_kind) VALUES (?,?,1,'manual')", h.tenant(), wo);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO work_order_escalations (tenant_id, work_order_id, level, trigger_kind) VALUES (?,?,1,'manual')", h.tenant(), wo))
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO work_order_escalations (tenant_id, work_order_id, level, trigger_kind) VALUES (?,?,4,'manual')", h.tenant(), wo))
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO work_order_escalations (tenant_id, work_order_id, level, trigger_kind) VALUES (?,?,2,'bogus')", h.tenant(), wo))
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        // rating range, uniqueness
        assertThatThrownBy(() -> jdbc.update("INSERT INTO work_order_ratings (tenant_id, work_order_id, rated_by_id, score) VALUES (?,?,?,6)", h.tenant(), wo, req))
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        jdbc.update("INSERT INTO work_order_ratings (tenant_id, work_order_id, rated_by_id, score) VALUES (?,?,?,5)", h.tenant(), wo, req);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO work_order_ratings (tenant_id, work_order_id, rated_by_id, score) VALUES (?,?,?,4)", h.tenant(), wo, req))
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        // one open info request
        String ins = "INSERT INTO work_order_info_requests (tenant_id, work_order_id, requested_by_id, question) VALUES (?,?,?,'q')";
        jdbc.update(ins, h.tenant(), wo, req);
        assertThatThrownBy(() -> jdbc.update(ins, h.tenant(), wo, req)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        // photo phase check
        String att = "INSERT INTO attachments (work_order_id, uploaded_by_id, file_name, file_path, tenant_id, photo_phase) VALUES (?,?,'a.png','/x',?,?)";
        jdbc.update(att, wo, req, h.tenant(), "before");
        jdbc.update(att, wo, req, h.tenant(), null);
        assertThatThrownBy(() -> jdbc.update(att, wo, req, h.tenant(), "during"))
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    // ── SLA pause end to end ──

    @Test
    void requestAndAnswerShiftTheStoredDeadlineByThePausedSeconds() {
        DbFixtures f = new DbFixtures(jdbc);
        DbFixtures.Hierarchy h = f.hierarchy();
        UUID reqId = f.user(h, "department");
        UUID sa = f.user(h, "system_admin");
        UUID wo = ticket(f, h, reqId, "under_review", "medium");
        TenantContext.set(h.tenant());
        Instant deadline = jdbc.queryForObject("SELECT sla_deadline FROM work_orders WHERE id = ?", java.sql.Timestamp.class, wo).toInstant();
        Instant t0 = Instant.now().minusSeconds(7200);
        workOrders.setClock(Clock.fixed(t0, ZoneOffset.UTC));
        try {
            workOrders.requestInfo(wo, "which room?", load(sa));
            assertThat(jdbc.queryForObject("SELECT sla_paused_at IS NOT NULL FROM work_orders WHERE id = ?", Boolean.class, wo)).isTrue();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM work_order_info_requests WHERE work_order_id = ? AND answered_at IS NULL", Integer.class, wo)).isEqualTo(1);
            // a second request while pending is refused
            assertThatThrownBy(() -> workOrders.requestInfo(wo, "again", load(sa))).isInstanceOf(WorkflowException.class);

            workOrders.setClock(Clock.fixed(t0.plusSeconds(3600), ZoneOffset.UTC));
            workOrders.provideInfo(wo, "room 12", load(reqId));
        } finally {
            workOrders.setClock(Clock.systemUTC());
        }
        assertThat(jdbc.queryForObject("SELECT sla_deadline FROM work_orders WHERE id = ?", java.sql.Timestamp.class, wo).toInstant())
            .isEqualTo(deadline.plusSeconds(3600));
        assertThat(jdbc.queryForObject("SELECT sla_paused_seconds FROM work_orders WHERE id = ?", Long.class, wo)).isEqualTo(3600L);
        assertThat(jdbc.queryForObject("SELECT sla_paused_at IS NULL FROM work_orders WHERE id = ?", Boolean.class, wo)).isTrue();
        Map<String, Object> row = jdbc.queryForMap("SELECT answer, paused_seconds, answered_by_id FROM work_order_info_requests WHERE work_order_id = ?", wo);
        assertThat(row.get("answer")).isEqualTo("room 12");
        assertThat(row.get("paused_seconds")).isEqualTo(3600L);
        assertThat(workOrders.infoRequests(wo, load(sa))).hasSize(1);
        // exactly two history rows: request and answer
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ticket_history WHERE work_order_id = ?", Integer.class, wo)).isEqualTo(2);
    }

    @Test
    void concurrentRequestsForInformationCreateExactlyOneOpenRequest() throws Exception {
        DbFixtures f = new DbFixtures(jdbc);
        DbFixtures.Hierarchy h = f.hierarchy();
        UUID reqId = f.user(h, "department");
        UUID sa = f.user(h, "system_admin");
        UUID wo = ticket(f, h, reqId, "under_review", "medium");
        int threads = 4;
        CyclicBarrier barrier = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<Boolean>> res = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            res.add(pool.submit(() -> {
                TenantContext.set(h.tenant());
                try {
                    barrier.await(10, TimeUnit.SECONDS);
                    workOrders.requestInfo(wo, "q", load(sa));
                    return true;
                } catch (WorkflowException | ConflictException | org.springframework.dao.ConcurrencyFailureException e) {
                    return false;
                } finally { TenantContext.clear(); }
            }));
        }
        int ok = 0;
        for (Future<Boolean> r : res) if (r.get(30, TimeUnit.SECONDS)) ok++;
        pool.shutdownNow();
        assertThat(ok).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM work_order_info_requests WHERE work_order_id = ?", Integer.class, wo)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ticket_history WHERE work_order_id = ?", Integer.class, wo)).isEqualTo(1);
    }

    @Test
    void cancelWhilePausedClosesThePauseAndTheRequest() {
        DbFixtures f = new DbFixtures(jdbc);
        DbFixtures.Hierarchy h = f.hierarchy();
        UUID reqId = f.user(h, "department");
        UUID sa = f.user(h, "system_admin");
        UUID wo = ticket(f, h, reqId, "under_review", "medium");
        TenantContext.set(h.tenant());
        workOrders.requestInfo(wo, "q", load(sa));
        var cancel = new com.smms.dto.request.TransitionRequest();
        cancel.setTargetStatus(TicketStatus.CANCELLED);
        cancel.setNote("duplicate");
        workOrders.transition(wo, cancel, load(sa));
        assertThat(jdbc.queryForObject("SELECT sla_paused_at IS NULL FROM work_orders WHERE id = ?", Boolean.class, wo)).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM work_order_info_requests WHERE work_order_id = ? AND cancelled_at IS NOT NULL AND answered_at IS NULL", Integer.class, wo)).isEqualTo(1);
    }

    @Test
    void concurrentTransitionsOneWinsTheRestGetAnOptimisticLockFailure() throws Exception {
        DbFixtures f = new DbFixtures(jdbc);
        DbFixtures.Hierarchy h = f.hierarchy();
        UUID reqId = f.user(h, "department");
        UUID sa = f.user(h, "system_admin");
        UUID wo = ticket(f, h, reqId, "new", "medium");
        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);
        List<Future<Boolean>> res = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            res.add(pool.submit(() -> {
                TenantContext.set(h.tenant());
                try {
                    var r = new com.smms.dto.request.TransitionRequest();
                    r.setTargetStatus(TicketStatus.CANCELLED);
                    r.setNote("x");
                    barrier.await(10, TimeUnit.SECONDS);
                    workOrders.transition(wo, r, load(sa));
                    return true;
                } catch (WorkflowException | org.springframework.dao.ConcurrencyFailureException e) {
                    return false; // lost the race (optimistic lock or deadlock victim: both are 409 in the API) or saw the new status
                } finally { TenantContext.clear(); }
            }));
        }
        int ok = 0;
        for (Future<Boolean> r : res) if (r.get(30, TimeUnit.SECONDS)) ok++;
        pool.shutdownNow();
        assertThat(ok).isEqualTo(1);
        // one history row for the single transition: no double audit
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ticket_history WHERE work_order_id = ?", Integer.class, wo)).isEqualTo(1);
    }

    // ── escalation ──

    @Test
    void insertIgnoreIsADuplicateGuardUnderConcurrency() throws Exception {
        DbFixtures f = new DbFixtures(jdbc);
        DbFixtures.Hierarchy h = f.hierarchy();
        UUID wo = ticket(f, h, f.user(h, "department"), "in_progress", "high");
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);
        List<Future<Integer>> res = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            res.add(pool.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return escalationRepo.insertIgnore(UUID.randomUUID(), h.tenant(), wo, 1, "sla_breach", null, "r");
            }));
        }
        int inserted = 0;
        for (Future<Integer> r : res) inserted += r.get(30, TimeUnit.SECONDS);
        pool.shutdownNow();
        assertThat(inserted).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM work_order_escalations WHERE work_order_id = ?", Integer.class, wo)).isEqualTo(1);
    }

    @Test
    void schedulerPassesBreachOnceThenLevelTwoOnceAndSkipPausedAndForeignRows() {
        DbFixtures f = new DbFixtures(jdbc);
        DbFixtures.Hierarchy h = f.hierarchy();
        UUID req = f.user(h, "department");
        UUID mm = f.user(h, "maintenance_manager");
        UUID hs = f.user(h, "hospital_supervisor");
        UUID rs = f.user(h, "region_supervisor");
        UUID ocm = f.user(h, "operating_company_manager");
        DbFixtures.Hierarchy other = f.hierarchy();
        UUID otherMm = f.user(other, "maintenance_manager");
        UUID overdue = ticket(f, h, req, "in_progress", "high");
        UUID paused = ticket(f, h, req, "under_review", "high");
        UUID low = ticket(f, h, req, "in_progress", "low");
        jdbc.update("UPDATE work_orders SET created_at = now() - interval '3 days', sla_deadline = now() - interval '1 hour' WHERE id IN (?, ?, ?)", overdue, paused, low);
        jdbc.update("UPDATE work_orders SET sla_paused_at = now() - interval '30 minutes' WHERE id = ?", paused);

        escalation.runScheduledPass();
        escalation.runScheduledPass(); // a second pass (or a second replica) changes nothing

        assertThat(levels(overdue)).containsExactly(1);
        assertThat(levels(low)).containsExactly(1);
        assertThat(levels(paused)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT sla_breached FROM work_orders WHERE id = ?", Boolean.class, paused)).isFalse();
        assertThat(jdbc.queryForObject("SELECT sla_breached FROM work_orders WHERE id = ?", Boolean.class, overdue)).isTrue();
        // high priority: MM and HS of the center were told exactly once; low priority nobody; other tenant nobody
        assertThat(notifications(mm, overdue)).isEqualTo(1);
        assertThat(notifications(hs, overdue)).isEqualTo(1);
        assertThat(notifications(mm, low)).isZero();
        assertThat(notifications(otherMm, overdue)).isZero();
        assertThat(jdbc.queryForObject("SELECT notified_count FROM work_order_escalations WHERE work_order_id = ? AND level = 1", Integer.class, overdue)).isEqualTo(2);

        // level 2 after deadline + 0.5 * 8 h = 4 h
        jdbc.update("UPDATE work_orders SET created_at = now() - interval '3 days', sla_deadline = now() - interval '5 hours' WHERE id = ?", overdue);
        escalation.runScheduledPass();
        escalation.runScheduledPass();
        assertThat(levels(overdue)).containsExactly(1, 2);
        assertThat(notifications(rs, overdue)).isEqualTo(1);
        assertThat(notifications(ocm, overdue)).isEqualTo(1);
        assertThat(notifications(mm, overdue)).isEqualTo(1); // level 2 does not re-notify level-1 recipients
        // resuming the pause (deadline stays in the past) lets the next pass breach it exactly once
        jdbc.update("UPDATE work_orders SET sla_paused_at = NULL WHERE id = ?", paused);
        escalation.runScheduledPass();
        escalation.runScheduledPass();
        assertThat(levels(paused)).containsExactly(1);
    }

    @Test
    void legacyLevelOneRowsNeverGetAnAutomaticLevelTwo() {
        DbFixtures f = new DbFixtures(jdbc);
        DbFixtures.Hierarchy h = f.hierarchy();
        UUID req = f.user(h, "department");
        UUID ocm = f.user(h, "operating_company_manager");
        UUID wo = ticket(f, h, req, "in_progress", "critical");
        jdbc.update("UPDATE work_orders SET created_at = now() - interval '4 days', sla_deadline = now() - interval '2 days', sla_breached = true WHERE id = ?", wo);
        jdbc.update("INSERT INTO work_order_escalations (tenant_id, work_order_id, level, trigger_kind) VALUES (?,?,1,'legacy_breach')", h.tenant(), wo);
        escalation.runScheduledPass();
        assertThat(levels(wo)).containsExactly(1);
        assertThat(notifications(ocm, wo)).isZero();
    }

    @Test
    void manualEscalationClimbsToLevelThreeThenStops() {
        DbFixtures f = new DbFixtures(jdbc);
        DbFixtures.Hierarchy h = f.hierarchy();
        UUID req = f.user(h, "department");
        UUID sa = f.user(h, "system_admin");
        UUID wo = ticket(f, h, req, "in_progress", "medium");
        TenantContext.set(h.tenant());
        for (int level = 1; level <= 3; level++) {
            assertThat(escalation.escalate(wo, "needs a manager", load(sa)).level()).isEqualTo(level);
        }
        assertThatThrownBy(() -> escalation.escalate(wo, "needs a manager", load(sa))).isInstanceOf(WorkflowException.class);
        assertThat(levels(wo)).containsExactly(1, 2, 3);
        assertThat(escalation.list(wo, load(sa))).extracting(WorkflowDtos.EscalationResponse::level).containsExactly(1, 2, 3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ticket_history WHERE work_order_id = ? AND note LIKE 'Escalated to level%'", Integer.class, wo)).isEqualTo(3);
        assertThat(workOrders.getById(wo, load(sa)).getEscalationLevel()).isEqualTo(3);
    }

    // ── emergency ──

    @Test
    void emergencyTightensTheSlaOnceAndClearKeepsIt() {
        DbFixtures f = new DbFixtures(jdbc);
        DbFixtures.Hierarchy h = f.hierarchy();
        UUID req = f.user(h, "department");
        UUID sa = f.user(h, "system_admin");
        UUID wo = ticket(f, h, req, "in_progress", "low");
        TenantContext.set(h.tenant());
        Instant before = jdbc.queryForObject("SELECT sla_deadline FROM work_orders WHERE id = ?", java.sql.Timestamp.class, wo).toInstant();
        emergency.declare(wo, "fire in ward 3", load(sa));
        Map<String, Object> row = jdbc.queryForMap("SELECT priority::text p, is_emergency e, sla_deadline d, emergency_declared_by_id by FROM work_orders WHERE id = ?", wo);
        assertThat(row.get("p")).isEqualTo("critical");
        assertThat(row.get("e")).isEqualTo(true);
        assertThat(((java.sql.Timestamp) row.get("d")).toInstant()).isBefore(before);
        assertThat(row.get("by")).isEqualTo(sa);
        assertThat(levels(wo)).containsExactly(1);
        assertThatThrownBy(() -> emergency.declare(wo, "fire in ward 3", load(sa))).isInstanceOf(WorkflowException.class);
        Instant tightened = ((java.sql.Timestamp) row.get("d")).toInstant();
        emergency.clear(wo, "false alarm", load(sa));
        assertThat(jdbc.queryForObject("SELECT is_emergency FROM work_orders WHERE id = ?", Boolean.class, wo)).isFalse();
        assertThat(jdbc.queryForObject("SELECT sla_deadline FROM work_orders WHERE id = ?", java.sql.Timestamp.class, wo).toInstant()).isEqualTo(tightened);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ticket_history WHERE work_order_id = ?", Integer.class, wo)).isEqualTo(2);
    }

    // ── confirmation and rating ──

    @Test
    void confirmationAndRatingRulesWithRealConstraints() throws Exception {
        DbFixtures f = new DbFixtures(jdbc);
        DbFixtures.Hierarchy h = f.hierarchy();
        UUID req = f.user(h, "department");
        UUID other = f.user(h, "department");
        UUID wo = ticket(f, h, req, "completed", "medium");
        jdbc.update("UPDATE work_orders SET completed_date = now() WHERE id = ?", wo);
        TenantContext.set(h.tenant());
        // another department user cannot even see the ticket (own submissions only): 404, not 403
        assertThatThrownBy(() -> confirmation.confirm(wo, true, null, load(other))).isInstanceOf(ResourceNotFoundException.class);
        // not rateable before acceptance
        assertThatThrownBy(() -> rating.rate(wo, 5, "x", load(req))).isInstanceOf(WorkflowException.class);
        confirmation.confirm(wo, true, null, load(req));
        assertThat(jdbc.queryForObject("SELECT requester_confirmation FROM work_orders WHERE id = ?", String.class, wo)).isEqualTo("accepted");
        assertThatThrownBy(() -> confirmation.confirm(wo, false, "no", load(req))).isInstanceOf(WorkflowException.class);

        // 6 concurrent ratings: exactly one wins
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);
        AtomicInteger created = new AtomicInteger(), conflicts = new AtomicInteger();
        List<Future<?>> res = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            res.add(pool.submit(() -> {
                TenantContext.set(h.tenant());
                try {
                    barrier.await(10, TimeUnit.SECONDS);
                    rating.rate(wo, 4, "good", load(req));
                    created.incrementAndGet();
                } catch (ConflictException e) {
                    conflicts.incrementAndGet();
                } catch (Exception e) { throw new IllegalStateException(e); }
                finally { TenantContext.clear(); }
            }));
        }
        for (Future<?> r : res) r.get(30, TimeUnit.SECONDS);
        pool.shutdownNow();
        assertThat(created.get()).isEqualTo(1);
        assertThat(conflicts.get()).isEqualTo(threads - 1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM work_order_ratings WHERE work_order_id = ?", Integer.class, wo)).isEqualTo(1);
        assertThat(rating.get(wo, load(req)).score()).isEqualTo(4);
    }

    // ── public status link ──

    @Test
    void statusLinkLifecycleAgainstTheRealIndexes() {
        DbFixtures f = new DbFixtures(jdbc);
        DbFixtures.Hierarchy h = f.hierarchy();
        UUID req = f.user(h, "resident");
        UUID wo = ticket(f, h, req, "in_progress", "medium");
        TenantContext.set(h.tenant());
        WorkflowDtos.StatusLinkResponse first = statusLinks.create(wo, load(req));
        assertThat(statusLinks.lookup(first.token())).hasValueSatisfying(r -> {
            assertThat(r.statusGroup()).isEqualTo("in_progress");
            assertThat(r.completed()).isFalse();
        });
        assertThat(jdbc.queryForObject("SELECT count(*) FROM work_order_status_links WHERE work_order_id = ? AND token_hash = ?",
            Integer.class, wo, StatusLinkService.hash(first.token()))).isEqualTo(1);
        // the plain token is never stored
        assertThat(jdbc.queryForObject("SELECT count(*) FROM work_order_status_links WHERE token_hash = ?", Integer.class, first.token())).isZero();
        WorkflowDtos.StatusLinkResponse second = statusLinks.create(wo, load(req));
        assertThat(statusLinks.lookup(first.token())).isEmpty();      // rotated: old link revoked
        assertThat(statusLinks.lookup(second.token())).isPresent();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM work_order_status_links WHERE work_order_id = ? AND revoked_at IS NULL", Integer.class, wo)).isEqualTo(1);
        // expiry
        jdbc.update("UPDATE work_order_status_links SET created_at = now() - interval '2 days', expires_at = now() - interval '1 day' WHERE work_order_id = ? AND revoked_at IS NULL", wo);
        assertThat(statusLinks.lookup(second.token())).isEmpty();
        // a soft-deleted work order disappears
        WorkflowDtos.StatusLinkResponse third = statusLinks.create(wo, load(req));
        jdbc.update("UPDATE work_orders SET deleted_at = now() WHERE id = ?", wo);
        assertThat(statusLinks.lookup(third.token())).isEmpty();
    }

    // ── departments ──

    @Test
    void departmentScopeIsolationAndPagination() {
        DbFixtures f = new DbFixtures(jdbc);
        DbFixtures.Hierarchy a = f.hierarchy();
        DbFixtures.Hierarchy b = f.hierarchy();
        UUID hsA = f.user(a, "hospital_supervisor");
        UUID rsA = f.user(a, "region_supervisor");
        UUID saA = f.user(a, "system_admin");
        UUID hsB = f.user(b, "hospital_supervisor");
        UUID depB = f.department(b, "B1");

        TenantContext.set(a.tenant());
        CreateDepartmentRequest r = new CreateDepartmentRequest();
        r.setCenterId(a.center());
        r.setName("Radiology");
        r.setNameAr("الأشعة");
        r.setCode("rad");
        var created = departments.create(r, load(hsA));
        assertThat(created.getCode()).isEqualTo("RAD");
        // duplicate code (case-insensitive) in the center
        r.setCode("RAD");
        assertThatThrownBy(() -> departments.create(r, load(hsA))).isInstanceOf(ConflictException.class);
        // other tenant's center: 404; other tenant's department: 404 on every path
        r.setCenterId(b.center());
        r.setCode("X");
        assertThatThrownBy(() -> departments.create(r, load(saA))).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> departments.get(depB, load(saA))).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> departments.deactivate(depB, load(saA))).isInstanceOf(ResourceNotFoundException.class);
        // a hospital supervisor of tenant B (own context) cannot see A's department either
        TenantContext.set(b.tenant());
        assertThatThrownBy(() -> departments.get(created.getId(), load(hsB))).isInstanceOf(ResourceNotFoundException.class);
        assertThat(departments.list(null, null, null, PageRequest.of(0, 50), load(hsB)).getContent())
            .extracting(d -> d.getId()).containsExactly(depB);

        TenantContext.set(a.tenant());
        // add more rows to page through them
        for (int i = 0; i < 5; i++) {
            r.setCenterId(a.center());
            r.setCode("C" + i);
            r.setName("Clinic " + i);
            departments.create(r, load(hsA));
        }
        var page = departments.list(null, null, "clinic", PageRequest.of(0, 2, org.springframework.data.domain.Sort.by("name")), load(hsA));
        assertThat(page.getTotalElements()).isEqualTo(5);
        assertThat(page.getContent()).hasSize(2);
        assertThat(page.getTotalPages()).isEqualTo(3);
        // LIKE wildcards in q are literals
        assertThat(departments.list(null, null, "%", PageRequest.of(0, 10), load(hsA)).getTotalElements()).isZero();
        assertThat(departments.list(a.center(), true, null, PageRequest.of(0, 50), load(rsA)).getTotalElements()).isEqualTo(6);
        departments.deactivate(created.getId(), load(hsA));
        assertThat(departments.list(null, true, null, PageRequest.of(0, 50), load(hsA)).getTotalElements()).isEqualTo(5);
        // region supervisor of the same region reads; a region supervisor of another region does not
        DbFixtures.Hierarchy c = f.hierarchy();
        UUID rsOtherRegionSameTenant = f.user(c, "region_supervisor");
        jdbc.update("UPDATE users SET tenant_id = ? WHERE id = ?", a.tenant(), rsOtherRegionSameTenant);
        assertThat(departments.list(null, null, null, PageRequest.of(0, 50), load(rsOtherRegionSameTenant)).getTotalElements()).isZero();
        assertThatThrownBy(() -> departments.get(created.getId(), load(rsOtherRegionSameTenant))).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void userDepartmentLinkValidatesTenantCenterAndMembersAreListed() {
        DbFixtures f = new DbFixtures(jdbc);
        DbFixtures.Hierarchy a = f.hierarchy();
        DbFixtures.Hierarchy b = f.hierarchy();
        UUID hsA = f.user(a, "hospital_supervisor");
        UUID depUser = f.user(a, "department");
        UUID depA = f.department(a, "A1");
        UUID depB = f.department(b, "B1");
        UUID userB = f.user(b, "department");
        TenantContext.set(a.tenant());

        assertThat(departments.linkUser(depUser, depA, load(hsA)).getDepartmentId()).isEqualTo(depA);
        assertThat(jdbc.queryForObject("SELECT department_id FROM users WHERE id = ?", UUID.class, depUser)).isEqualTo(depA);
        assertThat(departments.members(depA, PageRequest.of(0, 10), load(hsA)).getContent())
            .extracting(DepartmentService.MemberResponse::id).containsExactly(depUser);
        // another tenant's department / user: 404 and nothing changes
        assertThatThrownBy(() -> departments.linkUser(depUser, depB, load(hsA))).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> departments.linkUser(userB, depA, load(hsA))).isInstanceOf(ResourceNotFoundException.class);
        assertThat(jdbc.queryForObject("SELECT department_id FROM users WHERE id = ?", UUID.class, depUser)).isEqualTo(depA);
        // same tenant, other center: 422
        UUID otherCenter = UUID.randomUUID();
        jdbc.update("INSERT INTO centers (id, tenant_id, region_id, name, name_ar, code) VALUES (?, ?, ?, 'C2', 'C2', ?)",
            otherCenter, a.tenant(), a.region(), "C" + otherCenter.toString().substring(0, 8));
        UUID depOther = UUID.randomUUID();
        jdbc.update("INSERT INTO departments (id, tenant_id, center_id, name, name_ar, code) VALUES (?, ?, ?, 'D', 'D', 'O1')", depOther, a.tenant(), otherCenter);
        assertThatThrownBy(() -> departments.linkUser(depUser, depOther, load(hsA))).isInstanceOf(WorkflowException.class);
        // unlink
        assertThat(departments.linkUser(depUser, null, load(hsA)).getDepartmentId()).isNull();
        assertThat(departments.members(depA, PageRequest.of(0, 10), load(hsA)).getTotalElements()).isZero();
    }

    private List<Integer> levels(UUID wo) {
        return jdbc.queryForList("SELECT level FROM work_order_escalations WHERE work_order_id = ? ORDER BY level", Integer.class, wo);
    }

    private int notifications(UUID user, UUID wo) {
        return jdbc.queryForObject("SELECT count(*) FROM notifications WHERE user_id = ? AND work_order_id = ? AND type = 'ESCALATION'",
            Integer.class, user, wo);
    }
}
