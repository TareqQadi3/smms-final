package com.smms.notify;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ErrorSanitizerTest {

    @Test
    void stripsAddressesTokensAndNumbers() {
        String s = ErrorSanitizer.sanitize("550 user bob@example.org rejected; Authorization: Bearer abc.def-123 phone +966 50 123 4567 https://u:p@host/x");
        assertThat(s).doesNotContain("bob@example.org").doesNotContain("abc.def-123").doesNotContain("123 4567").doesNotContain("u:p@");
    }

    @Test
    void truncatesAndHandlesNull() {
        assertThat(ErrorSanitizer.sanitize("x".repeat(2000))).hasSize(500);
        assertThat(ErrorSanitizer.sanitize(null)).isNull();
        assertThat(ErrorSanitizer.sanitize("a\r\nb")).isEqualTo("a b");
    }
}
