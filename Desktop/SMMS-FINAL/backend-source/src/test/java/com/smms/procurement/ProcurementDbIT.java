package com.smms.procurement;

import com.smms.entity.User;
import com.smms.exception.ConflictException;
import com.smms.exception.ResourceNotFoundException;
import com.smms.exception.WorkflowException;
import com.smms.idempotency.IdempotencyService.Outcome;
import com.smms.it.AbstractDbIT;
import com.smms.it.DbFixtures;
import com.smms.materials.dto.MaterialRequestDtos.*;
import com.smms.materials.entity.MaterialRequestStatus;
import com.smms.materials.service.MaterialRequestService;
import com.smms.procurement.dto.PurchaseOrderDtos.*;
import com.smms.procurement.dto.SupplierDtos.SupplierRequest;
import com.smms.procurement.dto.SupplierDtos.SupplierResponse;
import com.smms.procurement.entity.PurchaseOrderStatus;
import com.smms.procurement.service.PurchaseOrderService;
import com.smms.procurement.service.SupplierService;
import com.smms.reports.inventory.InventoryReportService;
import com.smms.repository.UserRepository;
import com.smms.util.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Database tests of procurement and material requests (run with -Pit against a throwaway PostgreSQL). */
class ProcurementDbIT extends AbstractDbIT {

    @Autowired JdbcTemplate jdbc;
    @Autowired UserRepository users;
    @Autowired SupplierService suppliers;
    @Autowired PurchaseOrderService pos;
    @Autowired MaterialRequestService mrs;
    @Autowired InventoryReportService reports;

    /** One tenant with a center, a warehouse, two items and users of every role used by the flows. */
    record World(DbFixtures.Hierarchy h, UUID wh, User warehouse, User mm, User ocm, User te, User cr,
                 UUID item1, UUID item2, UUID workOrder) {
        UUID tenant() { return h.tenant(); }
        UUID center() { return h.center(); }
    }

    @AfterEach
    void clear() { TenantContext.clear(); }

    private World world() {
        DbFixtures f = new DbFixtures(jdbc);
        DbFixtures.Hierarchy h = f.hierarchy();
        UUID wh = UUID.randomUUID();
        jdbc.update("INSERT INTO warehouses (id, tenant_id, center_id, code, name, name_ar) VALUES (?,?,?,?,?,?)",
            wh, h.tenant(), h.center(), "MAIN", "Main", "Main");
        UUID i1 = item(h, "A"), i2 = item(h, "B");
        UUID te = f.user(h, "technician"), cr = f.user(h, "control_room");
        UUID wo = f.workOrder(h, cr, "in_progress", "medium");
        jdbc.update("UPDATE work_orders SET assigned_to_id = ? WHERE id = ?", te, wo);
        TenantContext.set(h.tenant());
        return new World(h, wh, user(f.user(h, "warehouse")), user(f.user(h, "maintenance_manager")),
            user(f.user(h, "operating_company_manager")), user(te), user(cr), i1, i2, wo);
    }

    private User user(UUID id) { return users.findById(id).orElseThrow(); }

    private UUID item(DbFixtures.Hierarchy h, String sku) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO inventory_items (id, tenant_id, center_id, name, name_ar, sku, unit) VALUES (?,?,?,?,?,?,'pc')",
            id, h.tenant(), h.center(), "Item " + sku, "Item " + sku, sku + "-" + id.toString().substring(0, 6));
        return id;
    }

    /** Sets the balance and records the matching opening movement, so the ledger invariant holds. */
    private void stock(World w, UUID item, String qty) {
        BigDecimal old = onHand(w, item);
        jdbc.update("INSERT INTO stock_balances (tenant_id, item_id, warehouse_id, on_hand) VALUES (?,?,?,?) "
            + "ON CONFLICT (item_id, warehouse_id) DO UPDATE SET on_hand = EXCLUDED.on_hand",
            w.tenant(), item, w.wh(), new BigDecimal(qty));
        BigDecimal delta = new BigDecimal(qty).subtract(old);
        jdbc.update("INSERT INTO inventory_movements (tenant_id, item_id, type, quantity, warehouse_id, movement_kind, balance_after) "
            + "VALUES (?,?,'stock_in'::inventory_movement_type,?,?,'opening',?)", w.tenant(), item, delta, w.wh(), new BigDecimal(qty));
    }

    private BigDecimal onHand(World w, UUID item) {
        return jdbc.queryForObject("SELECT COALESCE(sum(on_hand),0) FROM stock_balances WHERE item_id = ? AND warehouse_id = ?",
            BigDecimal.class, item, w.wh());
    }

    private BigDecimal reserved(World w, UUID item) {
        return jdbc.queryForObject("SELECT COALESCE(sum(reserved),0) FROM stock_balances WHERE item_id = ? AND warehouse_id = ?",
            BigDecimal.class, item, w.wh());
    }

    /** Ledger invariant: on_hand = inbound - outbound over the item's movements in the warehouse. */
    private void assertLedgerInvariant(World w, UUID item) {
        BigDecimal net = jdbc.queryForObject("SELECT COALESCE(sum(CASE WHEN movement_kind IN "
            + "('opening','receipt','return_in','adjustment_in','reversal_in') THEN quantity ELSE -quantity END),0) "
            + "FROM inventory_movements WHERE item_id = ? AND warehouse_id = ?", BigDecimal.class, item, w.wh());
        assertThat(onHand(w, item)).isEqualByComparingTo(net);
    }

    private SupplierResponse supplier(World w, String code) {
        return suppliers.create(w.ocm(), new SupplierRequest(null, code, "Supplier " + code, null, null, null, null));
    }

    private PurchaseOrderResponse draft(World w, SupplierResponse s, String q1, String q2) {
        return pos.create(w.warehouse(), new PurchaseOrderRequest(w.center(), s.id(), w.wh(), null, "note", null, null,
            List.of(new PoLineRequest(w.item1(), new BigDecimal(q1), new BigDecimal("5.00")),
                    new PoLineRequest(w.item2(), new BigDecimal(q2), null)), null));
    }

    private PurchaseOrderResponse approved(World w, String q1, String q2) {
        PurchaseOrderResponse po = draft(w, supplier(w, "S" + UUID.randomUUID().toString().substring(0, 6)), q1, q2);
        pos.submit(w.warehouse(), po.id(), null);
        return pos.approve(w.ocm(), po.id(), "ok", null);
    }

    private static String key() { return "k-" + UUID.randomUUID(); }

    private static ReceiveRequest receive(UUID line, String q) {
        return new ReceiveRequest("goods", List.of(new ReceiveLineRequest(line, new BigDecimal(q))));
    }

    @Test
    void purchaseOrderLifecycleReceivingAndIdempotency() {
        World w = world();
        PurchaseOrderResponse po = draft(w, supplier(w, "S1"), "10", "4");
        assertThat(po.poNumber()).matches("PO-\\d{4}-\\d{5}");
        assertThat(po.status()).isEqualTo(PurchaseOrderStatus.draft);

        pos.submit(w.warehouse(), po.id(), null);
        // separation of duties: the creator can never approve
        assertThatThrownBy(() -> pos.approve(w.warehouse(), po.id(), null, null)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> pos.approve(w.mm(), UUID.randomUUID(), null, null)).isInstanceOf(ResourceNotFoundException.class);
        // not approved yet: no receiving
        UUID l1 = pos.get(w.mm(), po.id()).lines().get(0).id();
        UUID l2 = pos.get(w.mm(), po.id()).lines().get(1).id();
        assertThatThrownBy(() -> pos.receive(w.warehouse(), po.id(), receive(l1, "1"), key())).isInstanceOf(WorkflowException.class);
        PurchaseOrderResponse ap = pos.approve(w.mm(), po.id(), "fine", null);
        assertThat(ap.status()).isEqualTo(PurchaseOrderStatus.approved);
        assertThat(ap.approvedBy().id()).isEqualTo(w.mm().getId());

        String k = key();
        Outcome<ReceiptResponse> first = pos.receive(w.warehouse(), po.id(), receive(l1, "6"), k);
        assertThat(first.replayed()).isFalse();
        assertThat(onHand(w, w.item1())).isEqualByComparingTo("6");
        assertThat(first.body().lines().get(0).movementId()).isNotNull();

        Outcome<ReceiptResponse> replay = pos.receive(w.warehouse(), po.id(), receive(l1, "6"), k);
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.body().id()).isEqualTo(first.body().id());
        assertThat(onHand(w, w.item1())).isEqualByComparingTo("6");
        // same key, different body
        assertThatThrownBy(() -> pos.receive(w.warehouse(), po.id(), receive(l1, "5"), k)).isInstanceOf(WorkflowException.class);
        // missing key
        assertThatThrownBy(() -> pos.receive(w.warehouse(), po.id(), receive(l1, "1"), null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(pos.get(w.mm(), po.id()).status()).isEqualTo(PurchaseOrderStatus.partially_received);

        // over-receipt: 4 outstanding, 5 offered; nothing changes (transaction rolled back)
        assertThatThrownBy(() -> pos.receive(w.warehouse(), po.id(), receive(l1, "5"), key()))
            .isInstanceOf(WorkflowException.class).hasMessageContaining("Over-receipt");
        assertThat(onHand(w, w.item1())).isEqualByComparingTo("6");
        // only the warehouse role receives
        assertThatThrownBy(() -> pos.receive(w.mm(), po.id(), receive(l1, "1"), key())).isInstanceOf(AccessDeniedException.class);

        pos.receive(w.warehouse(), po.id(), new ReceiveRequest(null, List.of(
            new ReceiveLineRequest(l1, new BigDecimal("4")), new ReceiveLineRequest(l2, new BigDecimal("4")))), key());
        PurchaseOrderResponse done = pos.get(w.mm(), po.id());
        assertThat(done.status()).isEqualTo(PurchaseOrderStatus.received);
        assertThat(done.receipts()).hasSize(2);
        assertThat(onHand(w, w.item1())).isEqualByComparingTo("10");
        assertThat(onHand(w, w.item2())).isEqualByComparingTo("4");
        assertLedgerInvariant(w, w.item1());
        assertLedgerInvariant(w, w.item2());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM po_receipt_lines WHERE movement_id IS NULL AND tenant_id = ?",
            Long.class, w.tenant())).isZero();
        assertThatThrownBy(() -> pos.close(w.mm(), po.id(), "done early", null)).isInstanceOf(WorkflowException.class);
    }

    @Test
    void concurrentReceiptsWithTheSameKeyPostStockOnce() throws Exception {
        World w = world();
        PurchaseOrderResponse po = approved(w, "10", "1");
        UUID l1 = pos.get(w.mm(), po.id()).lines().get(0).id();
        String k = key();
        int n = 8;
        ExecutorService ex = Executors.newFixedThreadPool(n);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Outcome<ReceiptResponse>>> fs = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            fs.add(ex.submit(() -> {
                TenantContext.set(w.tenant());
                try {
                    go.await();
                    return pos.receive(w.warehouse(), po.id(), receive(l1, "10"), k);
                } finally { TenantContext.clear(); }
            }));
        }
        go.countDown();
        int replays = 0;
        Set<UUID> receipts = new HashSet<>();
        for (Future<Outcome<ReceiptResponse>> f : fs) {
            Outcome<ReceiptResponse> o = f.get(60, TimeUnit.SECONDS);
            receipts.add(o.body().id());
            if (o.replayed()) replays++;
        }
        ex.shutdownNow();
        assertThat(receipts).hasSize(1);
        assertThat(replays).isEqualTo(n - 1);
        assertThat(onHand(w, w.item1())).isEqualByComparingTo("10");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM po_receipts WHERE po_id = ?", Long.class, po.id())).isEqualTo(1L);
        assertLedgerInvariant(w, w.item1());
    }

    @Test
    void concurrentDifferentReceiptsNeverOverReceive() throws Exception {
        World w = world();
        PurchaseOrderResponse po = approved(w, "10", "1");
        UUID l1 = pos.get(w.mm(), po.id()).lines().get(0).id();
        int n = 8; // 8 x 2 offered against 10 outstanding: exactly 5 fit
        ExecutorService ex = Executors.newFixedThreadPool(n);
        List<Future<Boolean>> fs = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            fs.add(ex.submit(() -> {
                TenantContext.set(w.tenant());
                try {
                    pos.receive(w.warehouse(), po.id(), receive(l1, "2"), key());
                    return true;
                } catch (WorkflowException e) {
                    return false;
                } finally { TenantContext.clear(); }
            }));
        }
        long ok = 0;
        for (Future<Boolean> f : fs) if (f.get(60, TimeUnit.SECONDS)) ok++;
        ex.shutdownNow();
        assertThat(ok).isEqualTo(5);
        assertThat(onHand(w, w.item1())).isEqualByComparingTo("10");
        assertThat(pos.get(w.mm(), po.id()).lines().get(0).qtyReceived()).isEqualByComparingTo("10");
    }

    @Test
    void materialRequestFullFlowWithStockArithmetic() {
        World w = world();
        stock(w, w.item1(), "20");
        stock(w, w.item2(), "20");
        // non-assigned technician cannot even see the work order
        User other = user(new DbFixtures(jdbc).user(w.h(), "technician"));
        assertThatThrownBy(() -> mrs.create(other, w.workOrder(), new CreateRequest(w.wh(), null,
            List.of(new CreateLine(w.item1(), BigDecimal.ONE))))).isInstanceOf(ResourceNotFoundException.class);

        MaterialRequestResponse mr = mrs.create(w.te(), w.workOrder(), new CreateRequest(w.wh(), "need",
            List.of(new CreateLine(w.item1(), new BigDecimal("5")), new CreateLine(w.item2(), new BigDecimal("3")))));
        assertThat(mr.requestNumber()).matches("MR-\\d{4}-\\d{5}");
        assertThat(mr.status()).isEqualTo(MaterialRequestStatus.submitted);
        assertThat(jdbc.queryForObject("SELECT needs_materials FROM work_orders WHERE id = ?", Boolean.class, w.workOrder())).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ticket_history WHERE work_order_id = ? AND stage = 'note'",
            Long.class, w.workOrder())).isEqualTo(1L);
        UUID i1l = lineOf(mr, w.item1()), i2l = lineOf(mr, w.item2());

        // warehouse cannot approve; technician cannot approve
        assertThatThrownBy(() -> mrs.approve(w.warehouse(), mr.id(), null)).isInstanceOf(AccessDeniedException.class);
        MaterialRequestResponse ap = mrs.approve(w.mm(), mr.id(), new ApproveRequest("ok", List.of(
            new ApproveLine(i1l, new BigDecimal("5")), new ApproveLine(i2l, new BigDecimal("2"))), null));
        assertThat(ap.status()).isEqualTo(MaterialRequestStatus.approved);

        Outcome<MaterialRequestResponse> res = mrs.reserve(w.warehouse(), mr.id(), null, key());
        assertThat(res.body().status()).isEqualTo(MaterialRequestStatus.reserved);
        assertThat(reserved(w, w.item1())).isEqualByComparingTo("5");
        assertThat(reserved(w, w.item2())).isEqualByComparingTo("2");

        String k = key();
        IssueRequest i3 = new IssueRequest(List.of(new LineQuantity(i1l, new BigDecimal("3"))), null);
        Outcome<MaterialRequestResponse> iss = mrs.issue(w.warehouse(), mr.id(), i3, k);
        assertThat(iss.body().status()).isEqualTo(MaterialRequestStatus.issued);
        assertThat(onHand(w, w.item1())).isEqualByComparingTo("17");
        assertThat(reserved(w, w.item1())).isEqualByComparingTo("2");
        assertThat(mrs.issue(w.warehouse(), mr.id(), i3, k).replayed()).isTrue();
        assertThat(onHand(w, w.item1())).isEqualByComparingTo("17");
        // above the approved quantity
        assertThatThrownBy(() -> mrs.issue(w.warehouse(), mr.id(),
            new IssueRequest(List.of(new LineQuantity(i1l, new BigDecimal("3"))), null), key())).isInstanceOf(WorkflowException.class);
        assertThat(onHand(w, w.item1())).isEqualByComparingTo("17");

        mrs.issue(w.warehouse(), mr.id(), new IssueRequest(List.of(new LineQuantity(i1l, new BigDecimal("2")),
            new LineQuantity(i2l, new BigDecimal("2"))), null), key());
        assertThat(reserved(w, w.item1())).isEqualByComparingTo("0");
        assertThat(reserved(w, w.item2())).isEqualByComparingTo("0");
        assertThat(onHand(w, w.item2())).isEqualByComparingTo("18");

        // only the assigned technician consumes, never more than outstanding
        assertThatThrownBy(() -> mrs.consume(w.cr(), mr.id(), new ConsumeRequest(List.of(
            new LineQuantity(i1l, BigDecimal.ONE)), null, null), null)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> mrs.consume(w.te(), mr.id(), new ConsumeRequest(List.of(
            new LineQuantity(i1l, new BigDecimal("6"))), null, null), null)).isInstanceOf(WorkflowException.class);
        mrs.consume(w.te(), mr.id(), new ConsumeRequest(List.of(new LineQuantity(i1l, new BigDecimal("4")),
            new LineQuantity(i2l, new BigDecimal("1"))), "used", null), null);
        assertThat(mrs.get(w.mm(), mr.id()).status()).isEqualTo(MaterialRequestStatus.issued);

        // warehouse takes back the rest: 1 of item1 and 1 of item2 -> everything accounted, auto close
        Outcome<MaterialRequestResponse> ret = mrs.giveBack(w.warehouse(), mr.id(), new ReturnRequest(List.of(
            new LineQuantity(i1l, BigDecimal.ONE), new LineQuantity(i2l, BigDecimal.ONE)), "unused", null), key());
        assertThat(ret.body().status()).isEqualTo(MaterialRequestStatus.closed);
        assertThat(onHand(w, w.item1())).isEqualByComparingTo("16");
        assertThat(onHand(w, w.item2())).isEqualByComparingTo("19");
        assertLedgerInvariant(w, w.item1());
        assertLedgerInvariant(w, w.item2());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory_movements WHERE work_order_id = ? AND "
            + "material_request_line_id IS NOT NULL", Long.class, w.workOrder())).isEqualTo(5L);
        // traceability: work order -> material requests
        assertThat(mrs.listForWorkOrder(w.te(), w.workOrder())).hasSize(1);
        assertThat(mrs.listForWorkOrder(w.warehouse(), w.workOrder())).hasSize(1);
        // closed is terminal
        assertThatThrownBy(() -> mrs.cancel(w.mm(), mr.id(), new MrReasonRequest("too late now", null))).isInstanceOf(WorkflowException.class);

        // CHECK backstops of the table
        assertThatThrownBy(() -> jdbc.update("UPDATE material_request_lines SET qty_consumed = qty_issued + 1 WHERE id = ?", i1l))
            .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE material_request_lines SET qty_issued = qty_approved + 1 WHERE id = ?", i1l))
            .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE material_request_lines SET qty_approved = qty_requested + 1 WHERE id = ?", i1l))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void insufficientStockRollsReservationBackAndCancelReleases() {
        World w = world();
        stock(w, w.item1(), "3");
        stock(w, w.item2(), "10");
        MaterialRequestResponse mr = mrs.create(w.te(), w.workOrder(), new CreateRequest(w.wh(), null,
            List.of(new CreateLine(w.item1(), new BigDecimal("5")), new CreateLine(w.item2(), new BigDecimal("2")))));
        mrs.approve(w.mm(), mr.id(), null);
        assertThatThrownBy(() -> mrs.reserve(w.warehouse(), mr.id(), null, key())).isInstanceOf(ConflictException.class);
        assertThat(reserved(w, w.item2())).isEqualByComparingTo("0"); // all or nothing
        assertThat(mrs.get(w.mm(), mr.id()).status()).isEqualTo(MaterialRequestStatus.approved);

        // purchase order for the shortage (5 needed, 3 available => 2 short on item1; item2 is fine)
        SupplierResponse s = supplier(w, "S-SH");
        PurchaseOrderResponse po = mrs.createPurchaseOrder(w.warehouse(), mr.id(), new ShortagePoRequest(s.id()));
        assertThat(po.status()).isEqualTo(PurchaseOrderStatus.draft);
        assertThat(po.materialRequestId()).isEqualTo(mr.id());
        assertThat(po.lines()).hasSize(1);
        assertThat(po.lines().get(0).qtyOrdered()).isEqualByComparingTo("2");

        // stock arrives, reserve works, cancel releases the reservation
        stock(w, w.item1(), "10");
        mrs.reserve(w.warehouse(), mr.id(), null, key());
        assertThat(reserved(w, w.item1())).isEqualByComparingTo("5");
        MaterialRequestResponse c = mrs.cancel(w.mm(), mr.id(), new MrReasonRequest("not needed any more", null));
        assertThat(c.status()).isEqualTo(MaterialRequestStatus.cancelled);
        assertThat(reserved(w, w.item1())).isEqualByComparingTo("0");
        assertThat(reserved(w, w.item2())).isEqualByComparingTo("0");
    }

    @Test
    void tenantIsolationForSuppliersOrdersRequestsAndReports() {
        World a = world();
        PurchaseOrderResponse po = approved(a, "5", "1");
        stock(a, a.item1(), "9");
        MaterialRequestResponse mr = mrs.create(a.te(), a.workOrder(), new CreateRequest(a.wh(), null,
            List.of(new CreateLine(a.item1(), BigDecimal.ONE))));
        SupplierResponse sa = supplier(a, "ISO-A");
        pos.receive(a.warehouse(), po.id(), receive(pos.get(a.mm(), po.id()).lines().get(0).id(), "5"), key());

        World b = world(); // switches the tenant context to B
        assertThatThrownBy(() -> pos.get(b.mm(), po.id())).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> pos.approve(b.ocm(), po.id(), null, null)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> mrs.get(b.mm(), mr.id())).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> mrs.approve(b.mm(), mr.id(), null)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> suppliers.get(b.ocm(), sa.id())).isInstanceOf(ResourceNotFoundException.class);
        assertThat(pos.list(b.mm(), null, null, null, null, null, PurchaseOrderService.pageable(0, 20, null)).getTotalElements()).isZero();
        assertThat(suppliers.list(b.ocm(), null, null, SupplierService.pageable(0, 20, null)).getTotalElements()).isZero();
        assertThat(mrs.list(b.mm(), null, null, null, MaterialRequestService.pageable(0, 20, null)).getTotalElements()).isZero();
        // tenant B cannot build documents from tenant A's supplier, warehouse or items
        SupplierResponse sb = supplier(b, "ISO-B");
        assertThatThrownBy(() -> pos.create(b.warehouse(), new PurchaseOrderRequest(b.center(), sa.id(), b.wh(), null, null,
            null, null, List.of(new PoLineRequest(b.item1(), BigDecimal.ONE, null)), null))).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> pos.create(b.warehouse(), new PurchaseOrderRequest(b.center(), sb.id(), a.wh(), null, null,
            null, null, List.of(new PoLineRequest(b.item1(), BigDecimal.ONE, null)), null))).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> pos.create(b.warehouse(), new PurchaseOrderRequest(b.center(), sb.id(), b.wh(), null, null,
            null, null, List.of(new PoLineRequest(a.item1(), BigDecimal.ONE, null)), null))).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> pos.create(b.warehouse(), new PurchaseOrderRequest(a.center(), sb.id(), b.wh(), null, null,
            null, null, List.of(new PoLineRequest(b.item1(), BigDecimal.ONE, null)), null))).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> mrs.create(b.te(), a.workOrder(), new CreateRequest(b.wh(), null,
            List.of(new CreateLine(b.item1(), BigDecimal.ONE))))).isInstanceOf(ResourceNotFoundException.class);
        // reports of tenant B contain nothing of tenant A
        LocalDate today = LocalDate.now(DateRange.ZONE);
        assertThat(reports.movements(b.mm(), today.minusDays(1), today, null, null, null, null).rows()).isEmpty();
        assertThat(reports.stock(b.warehouse(), null, null).rows()).isEmpty();
        assertThat(reports.purchaseOrders(b.mm(), null, null, null, null).rows()).isEmpty();
        assertThat(reports.openMaterialRequests(b.mm(), null).rows()).isEmpty();
        assertThatThrownBy(() -> reports.stock(b.mm(), null, a.center())).isInstanceOf(ResourceNotFoundException.class);

        // and tenant A's reports see their own data
        TenantContext.set(a.tenant());
        assertThat(reports.stock(a.warehouse(), null, null).rows()).isNotEmpty();
        assertThat(reports.purchaseOrders(a.mm(), null, null, null, null).rows()).hasSize(1);
        assertThat(reports.openMaterialRequests(a.mm(), null).rows()).hasSize(1);
        assertThat(reports.movements(a.mm(), null, null, null, null, null, null).rows()).isNotEmpty();
        for (String g : List.of("workOrder", "item", "asset")) {
            assertThat(reports.consumption(a.mm(), null, null, g, null).columns()).isNotEmpty();
        }
        assertThatThrownBy(() -> reports.purchaseOrders(a.warehouse(), null, null, null, null)).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void purchaseOrderConstraintsAreEnforcedByTheDatabase() {
        World w = world();
        PurchaseOrderResponse po = approved(w, "5", "1");
        assertThatThrownBy(() -> jdbc.update("UPDATE purchase_orders SET approved_by_id = created_by_id WHERE id = ?", po.id()))
            .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE purchase_order_lines SET qty_received = qty_ordered + 1 WHERE po_id = ?", po.id()))
            .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE purchase_orders SET status = 'bogus' WHERE id = ?", po.id()))
            .isInstanceOf(DataIntegrityViolationException.class);
        // supplier of another tenant is refused by the composite FK
        World b = world();
        UUID bSupplier = supplier(b, "FK-B").id();
        assertThatThrownBy(() -> jdbc.update("UPDATE purchase_orders SET supplier_id = ? WHERE id = ?", bSupplier, po.id()))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    private static UUID lineOf(MaterialRequestResponse mr, UUID item) {
        return mr.lines().stream().filter(l -> l.item().id().equals(item)).findFirst().orElseThrow().id();
    }
}
