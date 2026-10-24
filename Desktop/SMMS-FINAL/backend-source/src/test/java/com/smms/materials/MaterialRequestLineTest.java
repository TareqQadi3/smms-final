package com.smms.materials;

import com.smms.exception.WorkflowException;
import com.smms.materials.entity.MaterialRequest;
import com.smms.materials.entity.MaterialRequestLine;
import com.smms.materials.entity.MaterialRequestStatus;
import com.smms.materials.service.MaterialRequestService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Material-request quantity arithmetic (the V38 CHECK constraints are the DB backstop). */
class MaterialRequestLineTest {

    private static BigDecimal d(String s) { return new BigDecimal(s); }

    private static MaterialRequestLine line(String requested) {
        MaterialRequestLine l = new MaterialRequestLine();
        l.setItemId(UUID.randomUUID());
        l.setQtyRequested(d(requested));
        return l;
    }

    @Test
    void approvalStaysWithinTheRequestedQuantity() {
        MaterialRequestLine l = line("5");
        l.approve(d("0"));
        l.approve(d("5"));
        assertThatThrownBy(() -> l.approve(d("5.001"))).isInstanceOf(WorkflowException.class);
        assertThatThrownBy(() -> l.approve(d("-1"))).isInstanceOf(WorkflowException.class);
        assertThat(l.getQtyApproved()).isEqualByComparingTo("5");
    }

    @Test
    void reserveThenPartialIssueConsumesTheReservationFirst() {
        MaterialRequestLine l = line("10");
        l.approve(d("8"));
        assertThat(l.reserveAll()).isEqualByComparingTo("8");
        assertThat(l.reserveAll()).isEqualByComparingTo("0"); // idempotent arithmetic
        assertThat(l.issue(d("3"))).isEqualByComparingTo("3");
        assertThat(l.getQtyReserved()).isEqualByComparingTo("5");
        assertThat(l.issue(d("5"))).isEqualByComparingTo("5");
        assertThat(l.getQtyReserved()).isEqualByComparingTo("0");
        assertThat(l.fullyIssued()).isTrue();
        assertThatThrownBy(() -> l.issue(d("0.001"))).isInstanceOf(WorkflowException.class);
    }

    @Test
    void issueWithoutReservationReportsNoReservedPart() {
        MaterialRequestLine l = line("4");
        l.approve(d("4"));
        assertThat(l.issue(d("4"))).isEqualByComparingTo("0");
    }

    @Test
    void issueHalfReservedSplitsTheReservedPart() {
        MaterialRequestLine l = line("6");
        l.approve(d("6"));
        l.setQtyReserved(d("2")); // only 2 are held
        assertThat(l.issue(d("5"))).isEqualByComparingTo("2");
        assertThat(l.getQtyIssued()).isEqualByComparingTo("5");
        assertThat(l.getQtyReserved()).isEqualByComparingTo("0");
    }

    @Test
    void consumedPlusReturnedNeverExceedsIssued() {
        MaterialRequestLine l = line("10");
        l.approve(d("10"));
        l.issue(d("6"));
        l.consume(d("4"));
        assertThat(l.outstanding()).isEqualByComparingTo("2");
        assertThatThrownBy(() -> l.consume(d("2.5"))).isInstanceOf(WorkflowException.class);
        assertThatThrownBy(() -> l.giveBack(d("3"))).isInstanceOf(WorkflowException.class);
        l.giveBack(d("2"));
        assertThat(l.outstanding()).isEqualByComparingTo("0");
        assertThatThrownBy(() -> l.consume(d("0.001"))).isInstanceOf(WorkflowException.class);
    }

    @Test
    void releaseDropsTheHeldReservation() {
        MaterialRequestLine l = line("3");
        l.approve(d("3"));
        l.reserveAll();
        assertThat(l.releaseReservation()).isEqualByComparingTo("3");
        assertThat(l.getQtyReserved()).isEqualByComparingTo("0");
        assertThat(l.toReserve()).isEqualByComparingTo("3");
    }

    @Test
    void requestClosesOnlyWhenEverythingIsIssuedAndAccountedFor() {
        MaterialRequest mr = new MaterialRequest();
        MaterialRequestLine a = line("2"), b = line("3");
        mr.getLines().add(a);
        mr.getLines().add(b);
        a.approve(d("2"));
        b.approve(d("3"));
        mr.setStatus(MaterialRequestStatus.issued);
        a.issue(d("2"));
        b.issue(d("1"));
        a.consume(d("2"));
        b.consume(d("1"));
        assertThat(mr.readyToClose()).isFalse(); // line b is not fully issued yet
        b.issue(d("2"));
        assertThat(mr.readyToClose()).isFalse(); // 2 outstanding
        b.giveBack(d("2"));
        assertThat(mr.readyToClose()).isTrue();
        mr.setStatus(MaterialRequestStatus.approved);
        assertThat(mr.readyToClose()).isFalse();
    }

    @Test
    void shortageIsTheUnreservedNeedMinusAvailableAndNeverNegative() {
        assertThat(MaterialRequestService.shortage(d("5"), d("3"))).isEqualByComparingTo("2");
        assertThat(MaterialRequestService.shortage(d("5"), d("9"))).isEqualByComparingTo("0");
        assertThat(MaterialRequestService.shortage(d("5"), d("-2"))).isEqualByComparingTo("5");
    }
}
