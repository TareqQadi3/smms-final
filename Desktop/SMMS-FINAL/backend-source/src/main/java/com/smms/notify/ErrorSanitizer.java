package com.smms.notify;

import java.util.regex.Pattern;

/** Makes provider error text safe to store in the delivery log: no addresses, tokens or long numbers. */
public final class ErrorSanitizer {

    public static final int MAX_LENGTH = 500;

    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+'-]+@[A-Za-z0-9.-]+");
    private static final Pattern BEARER = Pattern.compile("(?i)(bearer|basic|token|password|secret|apikey|api-key|authorization)([\"'=:\\s]+)[^\\s\"',;]+");
    private static final Pattern URL_USERINFO = Pattern.compile("(?i)(https?://)[^/\\s@]+@");
    private static final Pattern LONG_DIGITS = Pattern.compile("\\+?\\d[\\d\\s().-]{6,}\\d");
    private static final Pattern CONTROL = Pattern.compile("[\\p{Cntrl}&&[^ ]]+");

    private ErrorSanitizer() {}

    public static String sanitize(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String s = CONTROL.matcher(raw).replaceAll(" ");
        s = URL_USERINFO.matcher(s).replaceAll("$1***@");
        s = EMAIL.matcher(s).replaceAll("***");
        s = BEARER.matcher(s).replaceAll("$1$2***");
        s = LONG_DIGITS.matcher(s).replaceAll("***");
        s = s.trim();
        return s.length() <= MAX_LENGTH ? s : s.substring(0, MAX_LENGTH);
    }
}
