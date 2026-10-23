package com.smms.inventory;

import com.smms.inventory.api.StockLedger;
import com.smms.inventory.api.StockLedger.MovementKind;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class StockLedgerContractTest {

    @Test
    void everyOperationMustJoinAnExistingTransaction() {
        for (Method m : StockLedger.class.getDeclaredMethods()) {
            Transactional t = m.getAnnotation(Transactional.class);
            assertThat(t).as(m.getName()).isNotNull();
            assertThat(t.propagation()).as(m.getName()).isEqualTo(Propagation.MANDATORY);
        }
        assertThat(StockLedger.class.getDeclaredMethods()).extracting(Method::getName)
            .containsExactlyInAnyOrder("requireWarehouse", "requireItem", "apply", "reserve", "release");
    }

    @Test
    void movementKindsMatchTheLedgerCheckConstraint() {
        assertThat(Arrays.stream(MovementKind.values()).map(Enum::name)).containsExactly(
            "opening", "receipt", "issue", "return_in", "adjustment_in", "adjustment_out", "reversal_in", "reversal_out");
    }

    @Test
    void directionOfEachKind() {
        assertThat(Arrays.stream(MovementKind.values()).filter(MovementKind::isInbound).map(Enum::name))
            .containsExactly("opening", "receipt", "return_in", "adjustment_in", "reversal_in");
    }
}
