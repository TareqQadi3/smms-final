package com.smms.reports.csv;

import com.smms.exception.WorkflowException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CsvWriterTest {

    @ParameterizedTest
    @ValueSource(strings = {"=1+1", "+1", "-1", "@SUM(A1)", "\t=1", "\r=1", "=HYPERLINK(\"http://x\")"})
    void formulaPrefixesAreNeutralised(String cell) {
        assertThat(CsvWriter.field(cell)).startsWith("\"'");
    }

    @Test
    void harmlessTextIsOnlyQuoted() {
        assertThat(CsvWriter.field("plain")).isEqualTo("\"plain\"");
        assertThat(CsvWriter.field("a=b")).isEqualTo("\"a=b\"");
        assertThat(CsvWriter.field("مضخة")).isEqualTo("\"مضخة\"");
    }

    @Test
    void quotesAreDoubled() {
        assertThat(CsvWriter.field("say \"hi\"")).isEqualTo("\"say \"\"hi\"\"\"");
    }

    @Test
    void neutralisedCellStillEscapesQuotes() {
        assertThat(CsvWriter.field("=\"a\"")).isEqualTo("\"'=\"\"a\"\"\"");
    }

    @Test
    void nullAndEmptyAreEmptyQuotedFields() {
        assertThat(CsvWriter.field(null)).isEqualTo("\"\"");
        assertThat(CsvWriter.field("")).isEqualTo("\"\"");
    }

    @Test
    void typedNumbersAreWrittenAsIsEvenWhenNegative() {
        assertThat(CsvWriter.field(-5)).isEqualTo("\"-5\"");
        assertThat(CsvWriter.field(new BigDecimal("1E+3"))).isEqualTo("\"1000\"");
        // the same text as a String is attacker-controllable, so it is neutralised
        assertThat(CsvWriter.field("-5")).isEqualTo("\"'-5\"");
    }

    @Test
    void newlinesInsideCellsStayInsideQuotes() {
        assertThat(CsvWriter.field("a\nb,c")).isEqualTo("\"a\nb,c\"");
    }

    @Test
    void dateAndEnumTextIsKept() {
        assertThat(CsvWriter.field(Instant.parse("2026-01-02T03:04:05Z"))).isEqualTo("\"2026-01-02T03:04:05Z\"");
        assertThat(CsvWriter.field(java.time.DayOfWeek.MONDAY)).isEqualTo("\"MONDAY\"");
    }

    @Test
    void documentHasBomHeaderAndCrlfRows() {
        String csv = new CsvWriter().toCsv(List.of("a", "=b"),
            List.of(Arrays.asList("x", 1), Arrays.asList(null, "-y")));
        assertThat(csv).isEqualTo("﻿\"a\",\"'=b\"\r\n\"x\",\"1\"\r\n\"\",\"'-y\"\r\n");
    }

    @Test
    void headersAreNeutralisedToo() {
        assertThat(new CsvWriter(10, false).toCsv(List.of("=cmd"), List.of()))
            .isEqualTo("\"'=cmd\"\r\n");
    }

    @Test
    void bomCanBeDisabled() {
        assertThat(new CsvWriter(10, false).toCsv(List.of("a"), List.of())).doesNotStartWith("﻿");
    }

    @Test
    void tooManyRowsIs422Style() {
        List<List<?>> rows = new ArrayList<>();
        for (int i = 0; i < 4; i++) rows.add(List.of(i));
        CsvWriter w = new CsvWriter(3, false);
        assertThatThrownBy(() -> w.toCsv(List.of("n"), rows))
            .isInstanceOf(WorkflowException.class).hasMessageContaining("narrow");
        // exactly the limit is fine
        assertThat(w.toCsv(List.of("n"), rows.subList(0, 3))).contains("\"2\"");
    }

    @Test
    void defaultLimitIsFiftyThousand() {
        assertThat(CsvWriter.DEFAULT_MAX_ROWS).isEqualTo(50_000);
    }
}
