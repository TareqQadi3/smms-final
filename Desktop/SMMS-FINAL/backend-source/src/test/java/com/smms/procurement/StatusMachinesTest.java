package com.smms.procurement;

import com.smms.materials.entity.MaterialRequestStatus;
import com.smms.procurement.entity.PurchaseOrderStatus;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static com.smms.procurement.entity.PurchaseOrderStatus.*;

/** Every (from, to) pair of both state machines is checked against the documented table. */
class StatusMachinesTest {

    private static final Map<PurchaseOrderStatus, Set<PurchaseOrderStatus>> PO = Map.of(
        draft, Set.of(submitted, cancelled),
        submitted, Set.of(approved, rejected, cancelled),
        approved, Set.of(partially_received, received, cancelled, closed),
        partially_received, Set.of(received, closed),
        rejected, Set.of(), received, Set.of(), cancelled, Set.of(), closed, Set.of());

    private static final Map<MaterialRequestStatus, Set<MaterialRequestStatus>> MR = Map.of(
        MaterialRequestStatus.submitted, Set.of(MaterialRequestStatus.approved, MaterialRequestStatus.rejected, MaterialRequestStatus.cancelled),
        MaterialRequestStatus.approved, Set.of(MaterialRequestStatus.reserved, MaterialRequestStatus.issued, MaterialRequestStatus.cancelled),
        MaterialRequestStatus.reserved, Set.of(MaterialRequestStatus.issued, MaterialRequestStatus.cancelled),
        MaterialRequestStatus.issued, Set.of(MaterialRequestStatus.closed),
        MaterialRequestStatus.rejected, Set.of(), MaterialRequestStatus.closed, Set.of(), MaterialRequestStatus.cancelled, Set.of());

    @Test
    void purchaseOrderTransitionsMatchTheTableForEveryPair() {
        for (PurchaseOrderStatus from : PurchaseOrderStatus.values()) {
            for (PurchaseOrderStatus to : PurchaseOrderStatus.values()) {
                assertThat(from.canMoveTo(to)).as(from + " -> " + to).isEqualTo(PO.get(from).contains(to));
            }
        }
        assertThat(PurchaseOrderStatus.values()).hasSameSizeAs(PO.keySet());
    }

    @Test
    void purchaseOrderTerminalStatesAndReceiving() {
        assertThat(PurchaseOrderStatus.values()).filteredOn(PurchaseOrderStatus::isTerminal)
            .containsExactlyInAnyOrder(rejected, received, cancelled, closed);
        assertThat(PurchaseOrderStatus.values()).filteredOn(PurchaseOrderStatus::canReceive)
            .containsExactlyInAnyOrder(approved, partially_received);
    }

    @Test
    void materialRequestTransitionsMatchTheTableForEveryPair() {
        for (MaterialRequestStatus from : MaterialRequestStatus.values()) {
            for (MaterialRequestStatus to : MaterialRequestStatus.values()) {
                assertThat(from.canMoveTo(to)).as(from + " -> " + to).isEqualTo(MR.get(from).contains(to));
            }
        }
        assertThat(MaterialRequestStatus.values()).hasSameSizeAs(MR.keySet());
        assertThat(MaterialRequestStatus.values()).filteredOn(MaterialRequestStatus::isTerminal)
            .containsExactlyInAnyOrder(MaterialRequestStatus.rejected, MaterialRequestStatus.closed, MaterialRequestStatus.cancelled);
    }
}
