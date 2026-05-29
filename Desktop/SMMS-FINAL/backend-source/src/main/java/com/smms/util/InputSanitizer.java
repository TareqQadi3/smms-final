package com.smms.util;

import java.util.regex.Pattern;

/**
 * Strips HTML tags and dangerous characters from user input before storage.
 * No external library needed — uses compiled regex patterns for performance.
 */
public final class InputSanitizer {

    private InputSanitizer() {}

    // Matches any HTML tag: <script>, </div>, <img onerror=...>, etc.
    private static final Pattern HTML_TAG    = Pattern.compile("<[^>]*>", Pattern.DOTALL);
    // Matches HTML entities like &lt; &gt; &#x27;
    private static final Pattern HTML_ENTITY = Pattern.compile("&[#a-zA-Z0-9]{2,6};");
    // Normalises multiple whitespace runs into a single space
    private static final Pattern MULTI_SPACE = Pattern.compile("\\s{2,}");

    /**
     * Strips HTML tags and entities from a string, then trims.
     * Returns null if input is null.
     */
    public static String clean(String input) {
        if (input == null) return null;
        String s = HTML_TAG.matcher(input).replaceAll("");
        s = HTML_ENTITY.matcher(s).replaceAll("");
        s = MULTI_SPACE.matcher(s).replaceAll(" ");
        return s.trim();
    }

    /** Cleans and enforces a max length — silently truncates. */
    public static String clean(String input, int maxLen) {
        String s = clean(input);
        if (s != null && s.length() > maxLen) s = s.substring(0, maxLen);
        return s;
    }
}
