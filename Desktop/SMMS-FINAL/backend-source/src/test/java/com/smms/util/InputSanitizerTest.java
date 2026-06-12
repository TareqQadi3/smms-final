package com.smms.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the XSS input sanitizer used before persisting user input.
 */
class InputSanitizerTest {

    @Test
    void nullInputReturnsNull() {
        assertThat(InputSanitizer.clean(null)).isNull();
        assertThat(InputSanitizer.clean(null, 10)).isNull();
    }

    @Test
    void plainTextPassesThrough() {
        assertThat(InputSanitizer.clean("hello world")).isEqualTo("hello world");
    }

    @Test
    void arabicTextIsPreserved() {
        assertThat(InputSanitizer.clean("صيانة مكيف الهواء")).isEqualTo("صيانة مكيف الهواء");
    }

    @ParameterizedTest(name = "strips: {0}")
    @CsvSource(delimiter = '|', value = {
        "<script>alert(1)</script>           | alert(1)",
        "<img src=x onerror=alert(1)>        | ''",
        "<b>bold</b> text                    | bold text",
        "before<div>middle</div>after        | beforemiddleafter",
    })
    void stripsHtmlTags(String input, String expected) {
        assertThat(InputSanitizer.clean(input.trim())).isEqualTo(expected.replace("''", ""));
    }

    @Test
    void stripsHtmlEntities() {
        assertThat(InputSanitizer.clean("a &lt;script&gt; b")).isEqualTo("a script b");
        assertThat(InputSanitizer.clean("quote: &#x27;")).isEqualTo("quote:");
    }

    @Test
    void normalizesWhitespaceAndTrims() {
        assertThat(InputSanitizer.clean("  too    many   spaces  ")).isEqualTo("too many spaces");
    }

    @Test
    void multilineTagIsStripped() {
        assertThat(InputSanitizer.clean("a<script\ntype=\"text/js\">b")).isEqualTo("ab");
    }

    @Test
    @DisplayName("max length truncates after cleaning")
    void enforcesMaxLength() {
        assertThat(InputSanitizer.clean("abcdefghij", 5)).isEqualTo("abcde");
        assertThat(InputSanitizer.clean("<b>abcdefghij</b>", 5)).isEqualTo("abcde");
        assertThat(InputSanitizer.clean("abc", 5)).isEqualTo("abc");
    }
}
