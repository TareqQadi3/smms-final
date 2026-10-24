package com.smms.procurement;

import com.smms.exception.WorkflowException;
import com.smms.procurement.entity.PurchaseOrder;
import com.smms.procurement.entity.PurchaseOrderLine;
import com.smms.procurement.entity.PurchaseOrderStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PurchaseOrderEntityTest {

    private static PurchaseOrderLine line(PurchaseOrder po, int no, String ordered, String price) {
        PurchaseOrderLine l = new PurchaseOrderLine();
        l.setPo(po);
        l.setLineNo(no);
        l.setItemId(UUID.randomUUID());
        l.setQtyOrdered(new BigDecimal(ordered));
        l.setUnitPrice(price == null ? null : new BigDecimal(price));
        po.getLines().add(l);
        return l;
    }

    @Test
    void overReceiptIsRefusedAndNothingChanges() {
        PurchaseOrder po = new PurchaseOrder();
        PurchaseOrderLine l = line(po, 1, "10", null);
        l.receive(new BigDecimal("6"));
        assertThatThrownBy(() -> l.receive(new BigDecimal("4.001"))).isInstanceOf(WorkflowException.class)
            .hasMessageContaining("Over-receipt");
        assertThat(l.getQtyReceived()).isEqualByComparingTo("6");
        l.receive(new BigDecimal("4"));
        assertThat(l.remaining()).isEqualByComparingTo("0");
        assertThatThrownBy(() -> l.receive(new BigDecimal("0.001"))).isInstanceOf(WorkflowException.class);
    }

    @Test
    void fullyReceivedNeedsEveryLineComplete() {
        PurchaseOrder po = new PurchaseOrder();
        assertThat(po.fullyReceived()).isFalse(); // no lines
        PurchaseOrderLine a = line(po, 1, "2", null), b = line(po, 2, "3", null);
        a.receive(new BigDecimal("2"));
        assertThat(po.fullyReceived()).isFalse();
        b.receive(new BigDecimal("3"));
        assertThat(po.fullyReceived()).isTrue();
    }

    @Test
    void totalSumsPricedLinesOnly() {
        PurchaseOrder po = new PurchaseOrder();
        line(po, 1, "2.5", "10.00");
        line(po, 2, "4", null);
        assertThat(po.total()).isEqualByComparingTo("25.00");
    }

    @Test
    void illegalTransitionIsA422WorkflowException() {
        PurchaseOrder po = new PurchaseOrder();
        assertThatThrownBy(() -> po.transitionTo(PurchaseOrderStatus.approved)).isInstanceOf(WorkflowException.class)
            .hasMessageContaining("draft").hasMessageContaining("approved");
        po.transitionTo(PurchaseOrderStatus.submitted);
        po.transitionTo(PurchaseOrderStatus.approved);
        po.transitionOrStay(PurchaseOrderStatus.partially_received);
        po.transitionOrStay(PurchaseOrderStatus.partially_received); // second partial receipt stays
        assertThat(po.getStatus()).isEqualTo(PurchaseOrderStatus.partially_received);
    }
}
