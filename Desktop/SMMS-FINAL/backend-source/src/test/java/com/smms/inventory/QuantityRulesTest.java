package com.smms.inventory;

import com.smms.inventory.service.QuantityRules;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QuantityRulesTest {

    @Test void acceptsPositiveWithinPrecision() {
        assertThat(QuantityRules.validate(new BigDecimal("1.250"), null)).isEqualByComparingTo("1.25");
        assertThat(QuantityRules.validate(new BigDecimal("1000000"), 0)).isEqualByComparingTo("1000000");
        assertThat(QuantityRules.validate(new BigDecimal("2.0"), 0)).isEqualByComparingTo("2");
    }

    @Test void rejectsZeroNegativeNullAndTooLarge() {
        for (String v : new String[] {"0", "-1", "1000000.001"}) {
            assertThatThrownBy(() -> QuantityRules.validate(new BigDecimal(v), null)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> QuantityRules.validate(null, null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void rejectsMoreDecimalsThanAllowed() {
        assertThatThrownBy(() -> QuantityRules.validate(new BigDecimal("0.0001"), null)).hasMessageContaining("3 decimal");
        assertThatThrownBy(() -> QuantityRules.validate(new BigDecimal("1.5"), 0)).hasMessageContaining("0 decimal");
        assertThatThrownBy(() -> QuantityRules.validate(new BigDecimal("1.25"), 1)).hasMessageContaining("1 decimal");
    }
}
