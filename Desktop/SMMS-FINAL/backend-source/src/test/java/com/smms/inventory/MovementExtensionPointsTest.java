package com.smms.inventory;

import com.smms.exception.WorkflowException;
import com.smms.materials.service.MaterialRequestMovementLinks;
import com.smms.procurement.service.PoReceiptMovementLinks;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Guard decisions of the material-request and purchase-order reversal guards (SQL itself runs in MovementLinksDbIT). */
class MovementExtensionPointsTest {

    private final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);

    private void status(String s) {
        when(jdbc.queryForList(anyString(), any(SqlParameterSource.class), eq(String.class))).thenReturn(List.of(s));
    }

    @Test
    void closedMaterialRequestBlocksReversal() {
        var g = new MaterialRequestMovementLinks(jdbc);
        status("closed");
        assertThatThrownBy(() -> g.assertReversible(UUID.randomUUID(), UUID.randomUUID(), null))
            .isInstanceOf(WorkflowException.class).hasMessageContaining("closed material request");
    }

    @Test
    void openMaterialRequestAndUnlinkedMovementsAreReversible() {
        var g = new MaterialRequestMovementLinks(jdbc);
        status("issued");
        g.assertReversible(UUID.randomUUID(), UUID.randomUUID(), null);
        g.assertReversible(UUID.randomUUID(), null, UUID.randomUUID());
    }

    @Test
    void closedPurchaseOrderBlocksReversalOthersPass() {
        var g = new PoReceiptMovementLinks(jdbc);
        status("closed");
        assertThatThrownBy(() -> g.assertReversible(UUID.randomUUID(), null, UUID.randomUUID()))
            .isInstanceOf(WorkflowException.class).hasMessageContaining("closed purchase order");
        status("partially_received");
        g.assertReversible(UUID.randomUUID(), null, UUID.randomUUID());
        g.assertReversible(UUID.randomUUID(), UUID.randomUUID(), null);
    }

    @Test
    void emptyIdSetResolvesToNothing() {
        assertThat(new MaterialRequestMovementLinks(jdbc).resolve(List.of())).isEmpty();
        assertThat(new PoReceiptMovementLinks(jdbc).resolve(List.of())).isEmpty();
    }
}
