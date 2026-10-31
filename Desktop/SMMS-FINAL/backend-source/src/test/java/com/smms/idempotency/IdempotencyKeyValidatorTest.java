package com.smms.idempotency;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdempotencyKeyValidatorTest {

    @ParameterizedTest
    @ValueSource(strings = {"12345678", "3f2504e0-4f89-11d3-9a0c-0305e82c3301", "ABCdef-12"})
    void acceptsValidKeys(String key) {
        assertThat(IdempotencyKeyValidator.require(key)).isEqualTo(key);
        assertThat(IdempotencyKeyValidator.optional(key)).isEqualTo(key);
    }

    @ParameterizedTest
    @ValueSource(strings = {"short", "has space1", "under_score1", "semi;colon1", "ключ-ключ-1", "x\ny12345678"})
    void rejectsMalformed(String key) {
        assertThatThrownBy(() -> IdempotencyKeyValidator.require(key)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> IdempotencyKeyValidator.optional(key)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsTooLong() {
        assertThatThrownBy(() -> IdempotencyKeyValidator.require("a".repeat(65)))
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(IdempotencyKeyValidator.require("a".repeat(64))).hasSize(64);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"  "})
    void requireRefusesMissingOptionalAllowsIt(String key) {
        assertThatThrownBy(() -> IdempotencyKeyValidator.require(key))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("required");
        assertThat(IdempotencyKeyValidator.optional(key)).isNull();
    }
}
