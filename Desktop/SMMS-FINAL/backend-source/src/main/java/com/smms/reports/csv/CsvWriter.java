package com.smms.reports.csv;

import com.smms.exception.WorkflowException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;

/**
 * CSV output safe against spreadsheet formula injection (OWASP CSV injection).
 *
 * <ul>
 *   <li>Every field is quoted, embedded quotes are doubled, rows end with CRLF.</li>
 *   <li>A text cell starting with {@code = + - @ TAB CR} is prefixed with {@code '} so a
 *       spreadsheet shows it as text instead of evaluating it. Typed numbers are written as is
 *       (their text cannot be attacker-controlled formulas).</li>
 *   <li>Optional UTF-8 BOM so Excel detects Arabic text.</li>
 *   <li>At most {@link #DEFAULT_MAX_ROWS} data rows by default; more raises a 422.</li>
 * </ul>
 */
public final class CsvWriter {

    public static final int DEFAULT_MAX_ROWS = 50_000;
    public static final String BOM = "﻿";
    private static final String EOL = "\r\n";

    private final int maxRows;
    private final boolean bom;

    public CsvWriter() { this(DEFAULT_MAX_ROWS, true); }

    public CsvWriter(int maxRows, boolean bom) {
        if (maxRows < 1) throw new IllegalArgumentException("maxRows must be positive");
        this.maxRows = maxRows;
        this.bom = bom;
    }

    /** Writes header and rows to {@code out}. */
    public void write(Appendable out, List<String> headers, Iterable<? extends List<?>> rows) {
        try {
            if (bom) out.append(BOM);
            writeRow(out, headers);
            int n = 0;
            for (List<?> row : rows) {
                if (++n > maxRows) {
                    throw new WorkflowException(
                        "Report has more than " + maxRows + " rows; narrow the date range");
                }
                writeRow(out, row);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Convenience: the whole document as a string. */
    public String toCsv(List<String> headers, Iterable<? extends List<?>> rows) {
        StringBuilder sb = new StringBuilder();
        write(sb, headers, rows);
        return sb.toString();
    }

    private static void writeRow(Appendable out, List<?> cells) throws IOException {
        boolean first = true;
        for (Object cell : cells) {
            if (!first) out.append(',');
            first = false;
            out.append(field(cell));
        }
        out.append(EOL);
    }

    /** One quoted, neutralised field. */
    public static String field(Object value) {
        String text = text(value);
        if (!(value instanceof Number) && needsNeutralising(text)) text = "'" + text;
        return '"' + text.replace("\"", "\"\"") + '"';
    }

    static boolean needsNeutralising(String text) {
        if (text.isEmpty()) return false;
        char c = text.charAt(0);
        return c == '=' || c == '+' || c == '-' || c == '@' || c == '\t' || c == '\r';
    }

    private static String text(Object value) {
        if (value == null) return "";
        if (value instanceof java.math.BigDecimal bd) return bd.toPlainString();
        return value.toString(); // Instant/LocalDate/enums: ISO text, never starts with a formula character
    }
}
