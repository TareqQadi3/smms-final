package com.smms.procurement;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smms.entity.Center;
import com.smms.entity.Region;
import com.smms.entity.User;
import com.smms.enums.UserRole;
import com.smms.exception.ConflictException;
import com.smms.exception.ResourceNotFoundException;
import com.smms.exception.WorkflowException;
import com.smms.idempotency.IdempotencyService;
import com.smms.procurement.dto.PurchaseOrderDtos.*;
import com.smms.procurement.entity.*;
import com.smms.procurement.repository.PoReceiptRepository;
import com.smms.procurement.repository.PurchaseOrderRepository;
import com.smms.procurement.service.*;
import com.smms.procurement.support.FakeStockLedger;
import com.smms.materials.repository.MaterialRequestRepository;
import com.smms.security.TenantSecurityService;
import com.smms.security.WorkOrderAccessPolicy;
import com.smms.support.Fixtures;
import com.smms.util.TenantContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Purchase-order rules with mocked persistence: SoD, thresholds, scope, state gates, receiving. */
class PurchaseOrderServiceTest {

    private final UUID tenant = UUID.randomUUID();
    private final Region region = Fixtures.region(tenant);
    private final Center center = Fixtures.center(region, tenant);
    private final Center otherCenter = Fixtures.center(region, tenant);
    private final UUID wh = UUID.randomUUID();

    private EntityManager em;
    private PurchaseOrderRepository repo;
    private PoReceiptRepository receipts;
    private ProcurementNotifier notifier;
    private ProcurementProperties props;
    private FakeStockLedger ledger;
    private PurchaseOrderService service;

    private User creator, approver, mm, ocm, warehouse;

    @BeforeEach
    void setUp() {
        em = mock(EntityManager.class);
        repo = mock(PurchaseOrderRepository.class);
        receipts = mock(PoReceiptRepository.class);
        notifier = mock(ProcurementNotifier.class);
        props = new ProcurementProperties();
        ledger = new FakeStockLedger();
        RefLookup lookup = mock(RefLookup.class);
        when(lookup.items(any())).thenReturn(java.util.Map.of());
        when(lookup.warehouse(any())).thenReturn(java.util.Optional.empty());
        when(repo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(repo.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));
        when(receipts.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));
        when(receipts.findByPoIdOrderByReceivedAtAsc(any())).thenReturn(List.of());
        service = new PurchaseOrderService(repo, receipts, mock(MaterialRequestRepository.class),
            mock(SupplierService.class), mock(TenantSecurityService.class), mock(WorkOrderAccessPolicy.class), ledger,
            lookup, mock(IdempotencyService.class), new ObjectMapper(), notifier, props, em);
        creator = Fixtures.user(UserRole.warehouse, center, null, tenant);
        warehouse = creator;
        approver = Fixtures.user(UserRole.operating_company_manager, center, null, tenant);
        ocm = approver;
        mm = Fixtures.user(UserRole.maintenance_manager, center, null, tenant);
    }

    @AfterEach
    void clear() { TenantContext.clear(); }

    private PurchaseOrder po(PurchaseOrderStatus status, String... orderedQtys) {
        PurchaseOrder po = new PurchaseOrder();
        po.setId(UUID.randomUUID());
        po.setTenantId(tenant);
        po.setCenter(center);
        po.setCreatedBy(creator);
        po.setWarehouseId(wh);
        po.setStatus(status);
        po.setVersion(3L);
        Supplier s = new Supplier();
        s.setId(UUID.randomUUID());
        s.setCode("S");
        s.setName("S");
        po.setSupplier(s);
        int no = 1;
        for (String q : orderedQtys) {
            PurchaseOrderLine l = new PurchaseOrderLine();
            l.setId(UUID.randomUUID());
            l.setPo(po);
            l.setLineNo(no++);
            l.setItemId(UUID.randomUUID());
            l.setQtyOrdered(new BigDecimal(q));
            l.setUnitPrice(new BigDecimal("10.00"));
            po.getLines().add(l);
            ledger.knownItems.add(l.getItemId());
        }
        ledger.knownWarehouses.add(wh);
        when(em.find(eq(PurchaseOrder.class), eq(po.getId()), eq(LockModeType.PESSIMISTIC_WRITE), anyMap())).thenReturn(po);
        when(em.find(PurchaseOrder.class, po.getId())).thenReturn(po);
        return po;
    }

    // ── segregation of duties and thresholds ─────────────────

    @Test
    void creatorCannotApproveOrRejectTheirOwnOrder() {
        User both = Fixtures.user(UserRole.maintenance_manager, center, null, tenant);
        PurchaseOrder po = po(PurchaseOrderStatus.submitted, "1");
        po.setCreatedBy(both);
        assertThatThrownBy(() -> service.approve(both, po.getId(), null, null)).isInstanceOf(AccessDeniedException.class)
            .hasMessageContaining("creator");
        assertThatThrownBy(() -> service.reject(both, po.getId(), "not needed", null)).isInstanceOf(AccessDeniedException.class);
        assertThat(po.getStatus()).isEqualTo(PurchaseOrderStatus.submitted);
        assertThat(po.getApprovedBy()).isNull();
    }

    @Test
    void anotherApproverApprovesAndTheCreatorIsNotified() {
        PurchaseOrder po = po(PurchaseOrderStatus.submitted, "1");
        service.approve(ocm, po.getId(), "ok", 3L);
        assertThat(po.getStatus()).isEqualTo(PurchaseOrderStatus.approved);
        assertThat(po.getApprovedBy()).isSameAs(ocm);
        assertThat(po.getApprovedAt()).isNotNull();
        verify(notifier).toUser(eq(creator), isNull(), eq("PO_APPROVED"), anyString());
    }

    @Test
    void rejectRecordsTheDecisionActorAndReason() {
        PurchaseOrder po = po(PurchaseOrderStatus.submitted, "1");
        service.reject(mm, po.getId(), "price too high", null);
        assertThat(po.getStatus()).isEqualTo(PurchaseOrderStatus.rejected);
        assertThat(po.getDecisionReason()).isEqualTo("price too high");
    }

    @Test
    void thresholdSendsExpensiveOrdersToTheOperatingCompanyManager() {
        props.setManagerApprovalLimit(new BigDecimal("100"));
        PurchaseOrder big = po(PurchaseOrderStatus.submitted, "11"); // 11 x 10 = 110 > 100
        assertThatThrownBy(() -> service.approve(mm, big.getId(), null, null)).isInstanceOf(AccessDeniedException.class)
            .hasMessageContaining("operating company manager");
        service.approve(ocm, big.getId(), null, null);
        assertThat(big.getStatus()).isEqualTo(PurchaseOrderStatus.approved);
        PurchaseOrder small = po(PurchaseOrderStatus.submitted, "10"); // exactly 100: not above
        service.approve(mm, small.getId(), null, null);
        assertThat(small.getStatus()).isEqualTo(PurchaseOrderStatus.approved);
    }

    @Test
    void noThresholdMeansEveryApproverMayApproveAnyAmount() {
        PurchaseOrder big = po(PurchaseOrderStatus.submitted, "100000");
        service.approve(mm, big.getId(), null, null);
        assertThat(big.getStatus()).isEqualTo(PurchaseOrderStatus.approved);
    }

    @Test
    void decisionsNeedTheRightRoleAndStatus() {
        PurchaseOrder po = po(PurchaseOrderStatus.submitted, "1");
        User te = Fixtures.user(UserRole.technician, center, null, tenant);
        assertThatThrownBy(() -> service.approve(warehouse, po.getId(), null, null)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.approve(te, po.getId(), null, null)).isInstanceOf(AccessDeniedException.class);
        PurchaseOrder draft = po(PurchaseOrderStatus.draft, "1");
        assertThatThrownBy(() -> service.approve(ocm, draft.getId(), null, null)).isInstanceOf(WorkflowException.class);
        PurchaseOrder done = po(PurchaseOrderStatus.approved, "1");
        assertThatThrownBy(() -> service.approve(ocm, done.getId(), null, null)).isInstanceOf(WorkflowException.class);
    }

    // ── scope and optimistic version ─────────────────────────

    @Test
    void ordersOutsideTheActorsCenterOrTenantAreNotFound() {
        PurchaseOrder po = po(PurchaseOrderStatus.submitted, "1");
        User foreignMm = Fixtures.user(UserRole.maintenance_manager, otherCenter, null, tenant);
        assertThatThrownBy(() -> service.approve(foreignMm, po.getId(), null, null)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.get(foreignMm, po.getId())).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.get(mm, UUID.randomUUID())).isInstanceOf(ResourceNotFoundException.class);
        TenantContext.set(UUID.randomUUID());
        assertThatThrownBy(() -> service.get(mm, po.getId())).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void regionSupervisorReadsTheRegionButCannotWrite() {
        PurchaseOrder po = po(PurchaseOrderStatus.submitted, "1");
        User rs = Fixtures.user(UserRole.region_supervisor, null, region, tenant);
        assertThat(service.get(rs, po.getId()).id()).isEqualTo(po.getId());
        assertThatThrownBy(() -> service.approve(rs, po.getId(), null, null)).isInstanceOf(AccessDeniedException.class);
        User foreignRs = Fixtures.user(UserRole.region_supervisor, null, Fixtures.region(tenant), tenant);
        assertThatThrownBy(() -> service.get(foreignRs, po.getId())).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void staleVersionIsAConflict() {
        PurchaseOrder po = po(PurchaseOrderStatus.submitted, "1");
        assertThatThrownBy(() -> service.approve(ocm, po.getId(), null, 2L)).isInstanceOf(ConflictException.class);
        assertThat(po.getStatus()).isEqualTo(PurchaseOrderStatus.submitted);
    }

    // ── draft rules, cancel, close ───────────────────────────

    @Test
    void onlyDraftsAreEditableAndSubmitNeedsLines() {
        PurchaseOrder sub = po(PurchaseOrderStatus.submitted, "1");
        PurchaseOrderRequest req = new PurchaseOrderRequest(center.getId(), UUID.randomUUID(), wh, null, null, null, null,
            List.of(new PoLineRequest(UUID.randomUUID(), BigDecimal.ONE, null)), null);
        assertThatThrownBy(() -> service.update(warehouse, sub.getId(), req)).isInstanceOf(WorkflowException.class);
        PurchaseOrder empty = po(PurchaseOrderStatus.draft);
        assertThatThrownBy(() -> service.submit(warehouse, empty.getId(), null)).isInstanceOf(WorkflowException.class);
        PurchaseOrder ok = po(PurchaseOrderStatus.draft, "1");
        service.submit(warehouse, ok.getId(), null);
        assertThat(ok.getStatus()).isEqualTo(PurchaseOrderStatus.submitted);
        assertThat(ok.getSubmittedAt()).isNotNull();
        verify(notifier).toRoles(eq(tenant), eq(center.getId()), anySet(), eq(warehouse.getId()), isNull(), eq("PO_SUBMITTED"), anyString());
    }

    @Test
    void warehouseCancelsOnlyDraftsManagersCancelAnyOpenOrder() {
        assertThat(service.cancel(warehouse, po(PurchaseOrderStatus.draft, "1").getId(), "mistake made", null).status())
            .isEqualTo(PurchaseOrderStatus.cancelled);
        PurchaseOrder sub = po(PurchaseOrderStatus.submitted, "1");
        assertThatThrownBy(() -> service.cancel(warehouse, sub.getId(), "changed my mind", null)).isInstanceOf(AccessDeniedException.class);
        assertThat(service.cancel(mm, sub.getId(), "changed my mind", null).status()).isEqualTo(PurchaseOrderStatus.cancelled);
        assertThat(service.cancel(ocm, po(PurchaseOrderStatus.approved, "1").getId(), "supplier gone", null).status())
            .isEqualTo(PurchaseOrderStatus.cancelled);
        assertThatThrownBy(() -> service.cancel(mm, po(PurchaseOrderStatus.received, "1").getId(), "too late now", null))
            .isInstanceOf(WorkflowException.class);
        assertThatThrownBy(() -> service.cancel(mm, po(PurchaseOrderStatus.partially_received, "1").getId(), "too late now", null))
            .isInstanceOf(WorkflowException.class);
    }

    @Test
    void shortCloseIsForManagersAndOnlyAfterApproval() {
        PurchaseOrder part = po(PurchaseOrderStatus.partially_received, "1");
        assertThatThrownBy(() -> service.close(warehouse, part.getId(), "supplier cannot deliver", null)).isInstanceOf(AccessDeniedException.class);
        assertThat(service.close(mm, part.getId(), "supplier cannot deliver", null).status()).isEqualTo(PurchaseOrderStatus.closed);
        assertThat(service.close(ocm, po(PurchaseOrderStatus.approved, "1").getId(), "no longer needed", null).status())
            .isEqualTo(PurchaseOrderStatus.closed);
        assertThatThrownBy(() -> service.close(mm, po(PurchaseOrderStatus.submitted, "1").getId(), "no longer needed", null))
            .isInstanceOf(WorkflowException.class);
    }

    // ── receiving ────────────────────────────────────────────

    private ReceiveRequest rec(PurchaseOrderLine l, String q) {
        return new ReceiveRequest("n", List.of(new ReceiveLineRequest(l.getId(), new BigDecimal(q))));
    }

    @Test
    void partialThenFullReceiptPostsStockAndMovesTheStatus() {
        PurchaseOrder po = po(PurchaseOrderStatus.approved, "10", "4");
        PurchaseOrderLine a = po.getLines().get(0), b = po.getLines().get(1);
        service.doReceive(warehouse, po.getId(), rec(a, "6"), UUID.randomUUID());
        assertThat(po.getStatus()).isEqualTo(PurchaseOrderStatus.partially_received);
        assertThat(ledger.onHand(wh, a.getItemId())).isEqualByComparingTo("6");
        assertThat(ledger.applied.get(0).kind().name()).isEqualTo("receipt");
        assertThat(ledger.applied.get(0).warehouseId()).isEqualTo(wh);

        service.doReceive(warehouse, po.getId(), new ReceiveRequest(null, List.of(
            new ReceiveLineRequest(a.getId(), new BigDecimal("4")), new ReceiveLineRequest(b.getId(), new BigDecimal("4")))),
            UUID.randomUUID());
        assertThat(po.getStatus()).isEqualTo(PurchaseOrderStatus.received);
        assertThat(ledger.onHand(wh, a.getItemId())).isEqualByComparingTo("10");
        assertThat(ledger.onHand(wh, b.getItemId())).isEqualByComparingTo("4");
    }

    @Test
    void overReceiptIsRefusedBeforeAnyStockMoves() {
        PurchaseOrder po = po(PurchaseOrderStatus.approved, "10", "4");
        PurchaseOrderLine a = po.getLines().get(0), b = po.getLines().get(1);
        // line a is fine, line b exceeds: the whole receipt is refused
        assertThatThrownBy(() -> service.doReceive(warehouse, po.getId(), new ReceiveRequest(null, List.of(
            new ReceiveLineRequest(a.getId(), new BigDecimal("5")), new ReceiveLineRequest(b.getId(), new BigDecimal("4.001")))),
            UUID.randomUUID())).isInstanceOf(WorkflowException.class).hasMessageContaining("Over-receipt");
        assertThat(ledger.applied).isEmpty();
        assertThat(ledger.onHand(wh, a.getItemId())).isEqualByComparingTo("0");
    }

    @Test
    void receivingRejectsBadStatusDuplicateAndForeignLines() {
        PurchaseOrder draft = po(PurchaseOrderStatus.draft, "5");
        assertThatThrownBy(() -> service.doReceive(warehouse, draft.getId(), rec(draft.getLines().get(0), "1"), UUID.randomUUID()))
            .isInstanceOf(WorkflowException.class);
        PurchaseOrder po = po(PurchaseOrderStatus.approved, "5");
        PurchaseOrderLine l = po.getLines().get(0);
        assertThatThrownBy(() -> service.doReceive(warehouse, po.getId(), new ReceiveRequest(null, List.of(
            new ReceiveLineRequest(l.getId(), BigDecimal.ONE), new ReceiveLineRequest(l.getId(), BigDecimal.ONE))), UUID.randomUUID()))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.doReceive(warehouse, po.getId(), new ReceiveRequest(null, List.of(
            new ReceiveLineRequest(UUID.randomUUID(), BigDecimal.ONE))), UUID.randomUUID()))
            .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.doReceive(warehouse, po.getId(), rec(l, "1.0001"), UUID.randomUUID()))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.doReceive(warehouse, po.getId(), rec(l, "-1"), UUID.randomUUID()))
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(ledger.applied).isEmpty();
    }

    @Test
    void onlyTheWarehouseRoleReceives() {
        PurchaseOrder po = po(PurchaseOrderStatus.approved, "5");
        assertThatThrownBy(() -> service.receive(mm, po.getId(), rec(po.getLines().get(0), "1"), "key-12345678"))
            .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.receive(ocm, po.getId(), rec(po.getLines().get(0), "1"), "key-12345678"))
            .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void warehouseOfAnotherCenterCannotReceive() {
        PurchaseOrder po = po(PurchaseOrderStatus.approved, "5");
        User foreign = Fixtures.user(UserRole.warehouse, otherCenter, null, tenant);
        assertThatThrownBy(() -> service.doReceive(foreign, po.getId(), rec(po.getLines().get(0), "1"), UUID.randomUUID()))
            .isInstanceOf(ResourceNotFoundException.class);
    }
}
