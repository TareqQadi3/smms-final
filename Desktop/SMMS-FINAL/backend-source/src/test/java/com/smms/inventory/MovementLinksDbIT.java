package com.smms.inventory;

import com.smms.entity.User;
import com.smms.exception.WorkflowException;
import com.smms.inventory.dto.Dtos.MovementResponse;
import com.smms.inventory.dto.Dtos.ReverseRequest;
import com.smms.inventory.service.StockOperationsService;
import com.smms.inventory.service.StockQueryService;
import com.smms.it.AbstractDbIT;
import com.smms.it.DbFixtures;
import com.smms.materials.dto.MaterialRequestDtos.*;
import com.smms.materials.service.MaterialRequestService;
import com.smms.procurement.dto.PurchaseOrderDtos.*;
import com.smms.procurement.dto.SupplierDtos.SupplierRequest;
import com.smms.procurement.service.PurchaseOrderService;
import com.smms.procurement.service.SupplierService;
import com.smms.repository.UserRepository;
import com.smms.util.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** W2a/W2b contract: link provider and reversal guards against the real ledger. */
class MovementLinksDbIT extends AbstractDbIT {

    @Autowired JdbcTemplate jdbc;
    @Autowired UserRepository users;
    @Autowired MaterialRequestService mrs;
    @Autowired PurchaseOrderService pos;
    @Autowired SupplierService suppliers;
    @Autowired StockOperationsService ops;
    @Autowired StockQueryService queries;

    @AfterEach
    void clear() { TenantContext.clear(); }

    private User user(UUID id) { return users.findById(id).orElseThrow(); }

    private static String key() { return "k-" + UUID.randomUUID(); }

    private MovementResponse movement(User actor, UUID item, UUID wh, UUID id) {
        return queries.movements(actor, item, wh, null, null, null, null, 0, 100, List.of()).getContent().stream()
            .filter(m -> m.id().equals(id)).findFirst().orElseThrow();
    }

    @Test
    void materialRequestAndPurchaseOrderLinksAndClosedGuards() {
        DbFixtures f = new DbFixtures(jdbc);
        DbFixtures.Hierarchy h = f.hierarchy();
        UUID wh = UUID.randomUUID();
        jdbc.update("INSERT INTO warehouses (id, tenant_id, center_id, code, name, name_ar) VALUES (?,?,?,?,?,?)",
            wh, h.tenant(), h.center(), "MAIN", "Main", "Main");
        UUID item = UUID.randomUUID();
        jdbc.update("INSERT INTO inventory_items (id, tenant_id, center_id, name, name_ar, sku, unit) VALUES (?,?,?,?,?,?,'pc')",
            item, h.tenant(), h.center(), "Item", "Item", "L-" + item.toString().substring(0, 6));
        jdbc.update("INSERT INTO stock_balances (tenant_id, item_id, warehouse_id, on_hand) VALUES (?,?,?,20)", h.tenant(), item, wh);
        jdbc.update("INSERT INTO inventory_movements (tenant_id, item_id, type, quantity, warehouse_id, movement_kind, balance_after) "
            + "VALUES (?,?,'stock_in'::inventory_movement_type,20,?,'opening',20)", h.tenant(), item, wh);
        UUID teId = f.user(h, "technician"), crId = f.user(h, "control_room");
        UUID wo = f.workOrder(h, crId, "in_progress", "medium");
        jdbc.update("UPDATE work_orders SET assigned_to_id = ? WHERE id = ?", teId, wo);
        TenantContext.set(h.tenant());
        User warehouse = user(f.user(h, "warehouse")), mm = user(f.user(h, "maintenance_manager")),
            ocm = user(f.user(h, "operating_company_manager")), te = user(teId);

        // ── material request ──
        MaterialRequestResponse mr = mrs.create(te, wo, new CreateRequest(wh, "need", List.of(new CreateLine(item, new BigDecimal("5")))));
        UUID line = mr.lines().get(0).id();
        mrs.approve(mm, mr.id(), new ApproveRequest("ok", null, null));
        mrs.issue(warehouse, mr.id(), new IssueRequest(List.of(new LineQuantity(line, new BigDecimal("2"))), null), key());
        mrs.issue(warehouse, mr.id(), new IssueRequest(List.of(new LineQuantity(line, new BigDecimal("3"))), null), key());
        List<UUID> issues = jdbc.queryForList("SELECT id FROM inventory_movements WHERE material_request_line_id = ? ORDER BY seq", UUID.class, line);
        assertThat(issues).hasSize(2);
        assertThat(movement(warehouse, item, wh, issues.get(0)).materialRequestId()).isEqualTo(mr.id());

        MovementResponse rev = ops.reverse(mm, key(), issues.get(0), new ReverseRequest("wrong issue")).body();
        assertThat(rev.materialRequestId()).as("reversal linked through reversal_of_id").isEqualTo(mr.id());
        assertThat(rev.poId()).isNull();

        mrs.consume(te, mr.id(), new ConsumeRequest(List.of(new LineQuantity(line, new BigDecimal("3"))), "used", null), null);
        mrs.giveBack(warehouse, mr.id(), new ReturnRequest(List.of(new LineQuantity(line, new BigDecimal("2"))), "unused", null), key());
        assertThat(jdbc.queryForObject("SELECT status FROM material_requests WHERE id = ?", String.class, mr.id())).isEqualTo("closed");
        assertThatThrownBy(() -> ops.reverse(mm, key(), issues.get(1), new ReverseRequest("too late")))
            .isInstanceOf(WorkflowException.class).hasMessageContaining("closed material request");

        // ── purchase order ──
        var supplier = suppliers.create(ocm, new SupplierRequest(null, "SL" + UUID.randomUUID().toString().substring(0, 5), "Supplier", null, null, null, null));
        PurchaseOrderResponse po = pos.create(warehouse, new PurchaseOrderRequest(h.center(), supplier.id(), wh, null, "n", null, null,
            List.of(new PoLineRequest(item, new BigDecimal("10"), new BigDecimal("5.00"))), null));
        pos.submit(warehouse, po.id(), null);
        pos.approve(mm, po.id(), "ok", null);
        UUID pl = pos.get(mm, po.id()).lines().get(0).id();
        pos.receive(warehouse, po.id(), new ReceiveRequest("g", List.of(new ReceiveLineRequest(pl, new BigDecimal("4")))), key());
        pos.receive(warehouse, po.id(), new ReceiveRequest("g", List.of(new ReceiveLineRequest(pl, new BigDecimal("3")))), key());
        List<UUID> receipts = jdbc.queryForList("SELECT movement_id FROM po_receipt_lines WHERE po_line_id = ? ORDER BY movement_id IS NULL, (SELECT seq FROM inventory_movements WHERE id = movement_id)",
            UUID.class, pl);
        assertThat(receipts).hasSize(2);
        assertThat(movement(warehouse, item, wh, receipts.get(0)).poId()).isEqualTo(po.id());
        MovementResponse prev = ops.reverse(mm, key(), receipts.get(0), new ReverseRequest("wrong receipt")).body();
        assertThat(prev.poId()).isEqualTo(po.id());
        assertThat(prev.materialRequestId()).isNull();

        pos.close(mm, po.id(), "supplier cannot deliver the rest", null);
        assertThatThrownBy(() -> ops.reverse(mm, key(), receipts.get(1), new ReverseRequest("too late")))
            .isInstanceOf(WorkflowException.class).hasMessageContaining("closed purchase order");
    }
}
