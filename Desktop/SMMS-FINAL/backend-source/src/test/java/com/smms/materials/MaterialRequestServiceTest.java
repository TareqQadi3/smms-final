package com.smms.materials;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smms.entity.Center;
import com.smms.entity.Region;
import com.smms.entity.User;
import com.smms.entity.WorkOrder;
import com.smms.enums.TicketStatus;
import com.smms.enums.UserRole;
import com.smms.exception.ConflictException;
import com.smms.exception.ResourceNotFoundException;
import com.smms.exception.WorkflowException;
import com.smms.idempotency.IdempotencyService;
import com.smms.inventory.api.StockLedger.MovementKind;
import com.smms.materials.dto.MaterialRequestDtos.*;
import com.smms.materials.entity.MaterialRequest;
import com.smms.materials.entity.MaterialRequestLine;
import com.smms.materials.entity.MaterialRequestStatus;
import com.smms.materials.repository.MaterialRequestRepository;
import com.smms.materials.service.MaterialRequestService;
import com.smms.procurement.service.ProcurementNotifier;
import com.smms.procurement.service.PurchaseOrderService;
import com.smms.procurement.service.RefLookup;
import com.smms.procurement.support.FakeStockLedger;
import com.smms.repository.WorkOrderRepository;
import com.smms.security.WorkOrderAccessPolicy;
import com.smms.support.Fixtures;
import com.smms.workflow.WorkOrderHistoryWriter;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Material-request service rules with mocked persistence and an in-memory stock ledger. */
class MaterialRequestServiceTest {

    private final UUID tenant = UUID.randomUUID();
    private final Region region = Fixtures.region(tenant);
    private final Center center = Fixtures.center(region, tenant);
    private final Center otherCenter = Fixtures.center(region, tenant);
    private final UUID wh = UUID.randomUUID();
    private final UUID itemA = UUID.randomUUID(), itemB = UUID.randomUUID();

    private EntityManager em;
    private MaterialRequestRepository repo;
    private WorkOrderRepository woRepo;
    private WorkOrderAccessPolicy woAccess;
    private WorkOrderHistoryWriter history;
    private RefLookup lookup;
    private FakeStockLedger ledger;
    private MaterialRequestService service;

    private User te, cr, mm, ocm, em2, warehouse, otherTe;
    private WorkOrder wo;

    @BeforeEach
    void setUp() {
        em = mock(EntityManager.class);
        repo = mock(MaterialRequestRepository.class);
        woRepo = mock(WorkOrderRepository.class);
        woAccess = mock(WorkOrderAccessPolicy.class);
        history = mock(WorkOrderHistoryWriter.class);
        lookup = mock(RefLookup.class);
        ledger = new FakeStockLedger();
        ledger.knownItems.addAll(List.of(itemA, itemB));
        ledger.knownWarehouses.add(wh);
        when(repo.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));
        when(repo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(lookup.items(any())).thenReturn(java.util.Map.of());
        when(lookup.warehouse(wh)).thenReturn(Optional.of(
            new RefLookup.WarehouseInfo(wh, tenant, center.getId(), "MAIN", "Main", "Main", true)));
        service = new MaterialRequestService(repo, woRepo, woAccess, history, ledger, lookup,
            mock(IdempotencyService.class), new ObjectMapper(), mock(ProcurementNotifier.class),
            mock(PurchaseOrderService.class), mock(PlatformTransactionManager.class), em);

        te = Fixtures.user(UserRole.technician, center, null, tenant);
        otherTe = Fixtures.user(UserRole.technician, center, null, tenant);
        cr = Fixtures.user(UserRole.control_room, center, null, tenant);
        mm = Fixtures.user(UserRole.maintenance_manager, center, null, tenant);
        ocm = Fixtures.user(UserRole.operating_company_manager, center, null, tenant);
        em2 = Fixtures.user(UserRole.electrical_manager, center, null, tenant);
        warehouse = Fixtures.user(UserRole.warehouse, center, null, tenant);

        wo = new WorkOrder();
        wo.setId(UUID.randomUUID());
        wo.setTenantId(tenant);
        wo.setCenter(center);
        wo.setStatus(TicketStatus.IN_PROGRESS);
        wo.setAssignedTo(te);
        when(woAccess.requireReadable(eq(wo.getId()), any())).thenAnswer(i -> {
            User u = i.getArgument(1);
            if (u.getRole() == UserRole.technician && !u.getId().equals(te.getId())) {
                throw new ResourceNotFoundException("WorkOrder not found");
            }
            return wo;
        });
    }

    private MaterialRequest mr(MaterialRequestStatus status, String... approvedByItemAB) {
        MaterialRequest m = new MaterialRequest();
        m.setId(UUID.randomUUID());
        m.setTenantId(tenant);
        m.setCenter(center);
        m.setWorkOrder(wo);
        m.setWarehouseId(wh);
        m.setRequestedBy(te);
        m.setStatus(status);
        m.setVersion(1L);
        m.setRequestNumber("MR-2026-00001");
        UUID[] items = {itemA, itemB};
        for (int i = 0; i < approvedByItemAB.length; i++) {
            MaterialRequestLine l = new MaterialRequestLine();
            l.setId(UUID.randomUUID());
            l.setMr(m);
            l.setItemId(items[i]);
            l.setQtyRequested(new BigDecimal("10"));
            l.setQtyApproved(new BigDecimal(approvedByItemAB[i]));
            m.getLines().add(l);
        }
        when(em.find(eq(MaterialRequest.class), eq(m.getId()), eq(LockModeType.PESSIMISTIC_WRITE), anyMap())).thenReturn(m);
        when(em.find(MaterialRequest.class, m.getId())).thenReturn(m);
        return m;
    }

    private static MaterialRequestLine line(MaterialRequest m, UUID item) {
        return m.getLines().stream().filter(l -> l.getItemId().equals(item)).findFirst().orElseThrow();
    }

    private static CreateRequest create(UUID wh, UUID... items) {
        return new CreateRequest(wh, "n", java.util.Arrays.stream(items).map(i -> new CreateLine(i, BigDecimal.ONE)).toList());
    }

    // ── create ───────────────────────────────────────────────

    @Test
    void assignedTechnicianCreatesAndTheWorkOrderKeepsWarehouseVisibility() {
        MaterialRequestResponse r = service.create(te, wo.getId(), create(wh, itemA, itemB));
        assertThat(r.status()).isEqualTo(MaterialRequestStatus.submitted);
        assertThat(r.lines()).hasSize(2);
        assertThat(wo.isNeedsMaterials()).isTrue();
        verify(woRepo).save(wo);
        verify(history).note(eq(wo), eq(te), contains("submitted"));
    }

    @Test
    void creationGates() {
        // work order in the wrong status
        wo.setStatus(TicketStatus.ASSIGNED);
        assertThatThrownBy(() -> service.create(te, wo.getId(), create(wh, itemA))).isInstanceOf(WorkflowException.class);
        wo.setStatus(TicketStatus.WAITING_MATERIALS);
        service.create(te, wo.getId(), create(wh, itemA)); // allowed
        // a technician who is not assigned cannot see the work order at all
        assertThatThrownBy(() -> service.create(otherTe, wo.getId(), create(wh, itemA))).isInstanceOf(ResourceNotFoundException.class);
        // roles outside the matrix
        for (User u : List.of(warehouse, ocm, em2, Fixtures.user(UserRole.resident, null, null, tenant))) {
            assertThatThrownBy(() -> service.create(u, wo.getId(), create(wh, itemA))).isInstanceOf(AccessDeniedException.class);
        }
        // manager of another center
        User foreignMm = Fixtures.user(UserRole.maintenance_manager, otherCenter, null, tenant);
        assertThatThrownBy(() -> service.create(foreignMm, wo.getId(), create(wh, itemA))).isInstanceOf(ResourceNotFoundException.class);
        // technician assigned elsewhere after a reassignment
        wo.setAssignedTo(otherTe);
        assertThatThrownBy(() -> service.create(te, wo.getId(), create(wh, itemA))).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void creationValidatesWarehouseAndItems() {
        UUID foreignWarehouse = UUID.randomUUID();
        when(lookup.warehouse(foreignWarehouse)).thenReturn(Optional.of(
            new RefLookup.WarehouseInfo(foreignWarehouse, UUID.randomUUID(), center.getId(), "X", "X", "X", true)));
        assertThatThrownBy(() -> service.create(te, wo.getId(), create(foreignWarehouse, itemA))).isInstanceOf(ResourceNotFoundException.class);
        UUID otherCenterWarehouse = UUID.randomUUID();
        when(lookup.warehouse(otherCenterWarehouse)).thenReturn(Optional.of(
            new RefLookup.WarehouseInfo(otherCenterWarehouse, tenant, otherCenter.getId(), "Y", "Y", "Y", true)));
        assertThatThrownBy(() -> service.create(te, wo.getId(), create(otherCenterWarehouse, itemA))).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.create(te, wo.getId(), create(UUID.randomUUID(), itemA))).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.create(te, wo.getId(), create(wh, UUID.randomUUID()))).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.create(te, wo.getId(), create(wh, itemA, itemA))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.create(te, wo.getId(), new CreateRequest(wh, null,
            List.of(new CreateLine(itemA, new BigDecimal("0.0001")))))).isInstanceOf(IllegalArgumentException.class);
    }

    // ── approval ─────────────────────────────────────────────

    @Test
    void approvalRolesScopeAndDefaults() {
        MaterialRequest m = mr(MaterialRequestStatus.submitted, "0", "0");
        assertThatThrownBy(() -> service.approve(warehouse, m.getId(), null)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.approve(te, m.getId(), null)).isInstanceOf(AccessDeniedException.class);
        User foreign = Fixtures.user(UserRole.maintenance_manager, otherCenter, null, tenant);
        assertThatThrownBy(() -> service.approve(foreign, m.getId(), null)).isInstanceOf(ResourceNotFoundException.class);
        service.approve(em2, m.getId(), null); // electrical manager of the center may approve; default = all requested
        assertThat(m.getStatus()).isEqualTo(MaterialRequestStatus.approved);
        assertThat(m.getLines()).allSatisfy(l -> assertThat(l.getQtyApproved()).isEqualByComparingTo("10"));
        assertThat(m.getDecidedBy()).isSameAs(em2);
        assertThatThrownBy(() -> service.approve(mm, m.getId(), null)).isInstanceOf(WorkflowException.class); // already decided
    }

    @Test
    void partialApprovalGivesUnlistedLinesZeroAndNeverExceedsTheRequest() {
        MaterialRequest m = mr(MaterialRequestStatus.submitted, "0", "0");
        MaterialRequestLine a = line(m, itemA);
        assertThatThrownBy(() -> service.approve(mm, m.getId(), new ApproveRequest(null,
            List.of(new ApproveLine(a.getId(), new BigDecimal("10.001"))), null))).isInstanceOf(WorkflowException.class);
        assertThatThrownBy(() -> service.approve(mm, m.getId(), new ApproveRequest(null,
            List.of(new ApproveLine(UUID.randomUUID(), BigDecimal.ONE)), null))).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.approve(mm, m.getId(), new ApproveRequest(null,
            List.of(new ApproveLine(a.getId(), BigDecimal.ZERO)), null))).isInstanceOf(WorkflowException.class); // nothing approved
        service.approve(mm, m.getId(), new ApproveRequest("part", List.of(new ApproveLine(a.getId(), new BigDecimal("4"))), 1L));
        assertThat(a.getQtyApproved()).isEqualByComparingTo("4");
        assertThat(line(m, itemB).getQtyApproved()).isEqualByComparingTo("0");
    }

    @Test
    void rejectNeedsTheSubmittedState() {
        MaterialRequest m = mr(MaterialRequestStatus.submitted, "0");
        service.reject(ocm, m.getId(), new MrReasonRequest("not justified", null));
        assertThat(m.getStatus()).isEqualTo(MaterialRequestStatus.rejected);
        assertThatThrownBy(() -> service.reject(ocm, m.getId(), new MrReasonRequest("again please", null))).isInstanceOf(WorkflowException.class);
        MaterialRequest ap = mr(MaterialRequestStatus.approved, "5");
        assertThatThrownBy(() -> service.reject(ocm, ap.getId(), new MrReasonRequest("too late now", null))).isInstanceOf(WorkflowException.class);
    }

    @Test
    void staleVersionIsAConflict() {
        MaterialRequest m = mr(MaterialRequestStatus.submitted, "0");
        assertThatThrownBy(() -> service.approve(mm, m.getId(), new ApproveRequest(null, null, 99L))).isInstanceOf(ConflictException.class);
    }

    // ── reserve / issue ──────────────────────────────────────

    @Test
    void reserveHoldsStockAllOrNothing() {
        MaterialRequest m = mr(MaterialRequestStatus.approved, "5", "5");
        ledger.seed(wh, itemA, "20");
        ledger.seed(wh, itemB, "2"); // not enough for item B
        assertThatThrownBy(() -> service.doReserve(warehouse, m.getId(), null)).isInstanceOf(ConflictException.class);
        assertThat(m.getStatus()).isEqualTo(MaterialRequestStatus.approved);
        ledger.seed(wh, itemB, "9");
        ledger.reserved.clear();
        service.doReserve(warehouse, m.getId(), null);
        assertThat(m.getStatus()).isEqualTo(MaterialRequestStatus.reserved);
        assertThat(ledger.reserved(wh, itemA)).isEqualByComparingTo("5");
        assertThat(ledger.reserved(wh, itemB)).isEqualByComparingTo("5");
        assertThatThrownBy(() -> service.doReserve(warehouse, m.getId(), null)).isInstanceOf(WorkflowException.class);
        // other roles and centers
        MaterialRequest m2 = mr(MaterialRequestStatus.approved, "1");
        assertThatThrownBy(() -> service.reserve(mm, m2.getId(), null, "key-12345678")).isInstanceOf(AccessDeniedException.class);
        User foreignWh = Fixtures.user(UserRole.warehouse, otherCenter, null, tenant);
        assertThatThrownBy(() -> service.doReserve(foreignWh, m2.getId(), null)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void issueFromReservationAndDirectSplitIntoTwoLedgerRows() {
        MaterialRequest m = mr(MaterialRequestStatus.approved, "6");
        MaterialRequestLine a = line(m, itemA);
        ledger.seed(wh, itemA, "10");
        a.setQtyReserved(new BigDecimal("2"));
        ledger.reserved.put(new FakeStockLedger.Key(wh, itemA), new BigDecimal("2"));
        service.doIssue(warehouse, m.getId(), new IssueRequest(List.of(new LineQuantity(a.getId(), new BigDecimal("5"))), null));
        assertThat(ledger.applied).hasSize(2);
        assertThat(ledger.applied.get(0).consumeReservation()).isTrue();
        assertThat(ledger.applied.get(0).qty()).isEqualByComparingTo("2");
        assertThat(ledger.applied.get(1).consumeReservation()).isFalse();
        assertThat(ledger.applied.get(1).qty()).isEqualByComparingTo("3");
        assertThat(ledger.applied).allSatisfy(c -> {
            assertThat(c.kind()).isEqualTo(MovementKind.issue);
            assertThat(c.workOrderId()).isEqualTo(wo.getId());
            assertThat(c.materialRequestLineId()).isEqualTo(a.getId());
            assertThat(c.tenantId()).isEqualTo(tenant);
        });
        assertThat(ledger.onHand(wh, itemA)).isEqualByComparingTo("5");
        assertThat(ledger.reserved(wh, itemA)).isEqualByComparingTo("0");
        assertThat(m.getStatus()).isEqualTo(MaterialRequestStatus.issued);
        assertThat(a.getQtyIssued()).isEqualByComparingTo("5");
        verify(history).note(eq(wo), eq(warehouse), contains("issued"));
    }

    @Test
    void issueIsBoundedByApprovalStatusAndStock() {
        MaterialRequest m = mr(MaterialRequestStatus.approved, "4");
        MaterialRequestLine a = line(m, itemA);
        ledger.seed(wh, itemA, "3");
        // more than the approved quantity: 422; more than stock: 409; nothing changes
        assertThatThrownBy(() -> service.doIssue(warehouse, m.getId(), new IssueRequest(
            List.of(new LineQuantity(a.getId(), new BigDecimal("4.001"))), null))).isInstanceOf(WorkflowException.class);
        MaterialRequest m2 = mr(MaterialRequestStatus.approved, "4");
        assertThatThrownBy(() -> service.doIssue(warehouse, m2.getId(), new IssueRequest(
            List.of(new LineQuantity(line(m2, itemA).getId(), new BigDecimal("4"))), null))).isInstanceOf(ConflictException.class);
        assertThat(ledger.onHand(wh, itemA)).isEqualByComparingTo("3");
        // wrong states
        for (MaterialRequestStatus s : List.of(MaterialRequestStatus.submitted, MaterialRequestStatus.rejected,
                MaterialRequestStatus.closed, MaterialRequestStatus.cancelled)) {
            MaterialRequest bad = mr(s, "4");
            assertThatThrownBy(() -> service.doIssue(warehouse, bad.getId(), new IssueRequest(
                List.of(new LineQuantity(line(bad, itemA).getId(), BigDecimal.ONE)), null))).as(s.name()).isInstanceOf(WorkflowException.class);
        }
        // unknown / duplicate lines
        assertThatThrownBy(() -> service.doIssue(warehouse, m.getId(), new IssueRequest(
            List.of(new LineQuantity(UUID.randomUUID(), BigDecimal.ONE)), null))).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.doIssue(warehouse, m.getId(), new IssueRequest(
            List.of(new LineQuantity(a.getId(), BigDecimal.ONE), new LineQuantity(a.getId(), BigDecimal.ONE)), null)))
            .isInstanceOf(IllegalArgumentException.class);
    }

    // ── consume / return / close ─────────────────────────────

    private MaterialRequest issued(String issuedA) {
        MaterialRequest m = mr(MaterialRequestStatus.issued, issuedA);
        MaterialRequestLine a = line(m, itemA);
        a.setQtyIssued(new BigDecimal(issuedA));
        ledger.seed(wh, itemA, "0");
        return m;
    }

    @Test
    void onlyTheAssignedTechnicianConsumes() {
        MaterialRequest m = issued("5");
        MaterialRequestLine a = line(m, itemA);
        ConsumeRequest body = new ConsumeRequest(List.of(new LineQuantity(a.getId(), new BigDecimal("3"))), "used", null);
        // reassigned: the requester can still read but is no longer the assignee
        wo.setAssignedTo(otherTe);
        assertThatThrownBy(() -> service.doConsume(te, m.getId(), body)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.consume(mm, m.getId(), body, null)).isInstanceOf(AccessDeniedException.class); // role
        assertThatThrownBy(() -> service.consume(warehouse, m.getId(), body, null)).isInstanceOf(AccessDeniedException.class);
        wo.setAssignedTo(te);
        service.doConsume(te, m.getId(), body);
        assertThat(a.getQtyConsumed()).isEqualByComparingTo("3");
        assertThat(m.getStatus()).isEqualTo(MaterialRequestStatus.issued);
        // another technician (not assigned, not requester) gets 404
        assertThatThrownBy(() -> service.doConsume(otherTe, m.getId(), body)).isInstanceOf(ResourceNotFoundException.class);
        verify(history).note(eq(wo), eq(te), contains("consumed"));
    }

    @Test
    void consumptionAndReturnTogetherCloseTheRequest() {
        MaterialRequest m = issued("5");
        MaterialRequestLine a = line(m, itemA);
        service.doConsume(te, m.getId(), new ConsumeRequest(List.of(new LineQuantity(a.getId(), new BigDecimal("3"))), null, null));
        assertThatThrownBy(() -> service.doConsume(te, m.getId(), new ConsumeRequest(
            List.of(new LineQuantity(a.getId(), new BigDecimal("2.001"))), null, null))).isInstanceOf(WorkflowException.class);
        service.doReturn(warehouse, m.getId(), new ReturnRequest(List.of(new LineQuantity(a.getId(), new BigDecimal("2"))), "left over", null));
        assertThat(m.getStatus()).isEqualTo(MaterialRequestStatus.closed);
        assertThat(ledger.applied).hasSize(1);
        assertThat(ledger.applied.get(0).kind()).isEqualTo(MovementKind.return_in);
        assertThat(ledger.onHand(wh, itemA)).isEqualByComparingTo("2");
        // closed: nothing more
        assertThatThrownBy(() -> service.doReturn(warehouse, m.getId(), new ReturnRequest(
            List.of(new LineQuantity(a.getId(), BigDecimal.ONE)), "again please", null))).isInstanceOf(WorkflowException.class);
    }

    @Test
    void returnCannotExceedWhatIsOutstanding() {
        MaterialRequest m = issued("5");
        MaterialRequestLine a = line(m, itemA);
        a.setQtyConsumed(new BigDecimal("4"));
        assertThatThrownBy(() -> service.doReturn(warehouse, m.getId(), new ReturnRequest(
            List.of(new LineQuantity(a.getId(), new BigDecimal("2"))), "extra stock", null))).isInstanceOf(WorkflowException.class);
        assertThat(ledger.applied).isEmpty();
    }

    @Test
    void manualCloseNeedsNothingOutstandingAndReleasesReservations() {
        MaterialRequest m = issued("5");
        MaterialRequestLine a = line(m, itemA);
        assertThatThrownBy(() -> service.close(warehouse, m.getId(), null)).isInstanceOf(WorkflowException.class);
        a.setQtyConsumed(new BigDecimal("5"));
        a.setQtyApproved(new BigDecimal("8")); // 3 approved but never issued
        a.setQtyReserved(new BigDecimal("3"));
        ledger.seed(wh, itemA, "3");
        ledger.reserved.put(new FakeStockLedger.Key(wh, itemA), new BigDecimal("3"));
        assertThat(service.close(mm, m.getId(), null).status()).isEqualTo(MaterialRequestStatus.closed);
        assertThat(ledger.reserved(wh, itemA)).isEqualByComparingTo("0");
        assertThatThrownBy(() -> service.close(ocm, m.getId(), null)).isInstanceOf(AccessDeniedException.class);
    }

    // ── cancel ───────────────────────────────────────────────

    @Test
    void requesterCancelsOnlyWhileSubmittedAndReservationsAreReleased() {
        MaterialRequest sub = mr(MaterialRequestStatus.submitted, "0");
        assertThat(service.cancel(te, sub.getId(), new MrReasonRequest("no longer needed", null)).status())
            .isEqualTo(MaterialRequestStatus.cancelled);
        assertThat(sub.getCancelReason()).isEqualTo("no longer needed");

        MaterialRequest res = mr(MaterialRequestStatus.reserved, "4");
        MaterialRequestLine a = line(res, itemA);
        a.setQtyReserved(new BigDecimal("4"));
        ledger.seed(wh, itemA, "10");
        ledger.reserved.put(new FakeStockLedger.Key(wh, itemA), new BigDecimal("4"));
        assertThatThrownBy(() -> service.cancel(te, res.getId(), new MrReasonRequest("changed my mind", null))).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.cancel(ocm, res.getId(), new MrReasonRequest("changed my mind", null))).isInstanceOf(AccessDeniedException.class);
        service.cancel(warehouse, res.getId(), new MrReasonRequest("changed my mind", null));
        assertThat(res.getStatus()).isEqualTo(MaterialRequestStatus.cancelled);
        assertThat(ledger.reserved(wh, itemA)).isEqualByComparingTo("0");

        MaterialRequest issued = mr(MaterialRequestStatus.issued, "1");
        assertThatThrownBy(() -> service.cancel(mm, issued.getId(), new MrReasonRequest("too late for that", null))).isInstanceOf(WorkflowException.class);
    }

    // ── read scope ───────────────────────────────────────────

    @Test
    void readersAreScopedByRoleAndCenter() {
        MaterialRequest m = mr(MaterialRequestStatus.submitted, "0");
        for (User u : List.of(te, mm, ocm, em2, warehouse, Fixtures.user(UserRole.hospital_supervisor, center, null, tenant),
                Fixtures.user(UserRole.region_supervisor, null, region, tenant), Fixtures.user(UserRole.system_admin, null, null, tenant))) {
            assertThat(service.get(u, m.getId()).id()).as(u.getRole().name()).isEqualTo(m.getId());
        }
        for (User u : List.of(otherTe, Fixtures.user(UserRole.maintenance_manager, otherCenter, null, tenant),
                Fixtures.user(UserRole.warehouse, otherCenter, null, tenant),
                Fixtures.user(UserRole.region_supervisor, null, Fixtures.region(tenant), tenant))) {
            assertThatThrownBy(() -> service.get(u, m.getId())).as(u.getRole().name()).isInstanceOf(ResourceNotFoundException.class);
        }
        for (UserRole r : List.of(UserRole.resident, UserRole.department, UserRole.other)) {
            User u = Fixtures.user(r, center, null, tenant);
            assertThatThrownBy(() -> service.get(u, m.getId())).as(r.name()).isInstanceOf(AccessDeniedException.class);
        }
    }

    @Test
    void controlRoomReadsThroughTheWorkOrderOrAsRequester() {
        MaterialRequest m = mr(MaterialRequestStatus.submitted, "0");
        // work order readable by the control room operator
        assertThat(service.get(cr, m.getId()).id()).isEqualTo(m.getId());
        // not readable and not the requester: 404
        when(woAccess.requireReadable(eq(wo.getId()), eq(cr))).thenThrow(new ResourceNotFoundException("WorkOrder not found"));
        assertThatThrownBy(() -> service.get(cr, m.getId())).isInstanceOf(ResourceNotFoundException.class);
        m.setRequestedBy(cr);
        assertThat(service.get(cr, m.getId()).id()).isEqualTo(m.getId());
    }
}
