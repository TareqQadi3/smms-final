package com.smms.security;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;

/**
 * SECURITY LAYER — XSS & Injection Sanitization Filter
 *
 * Runs BEFORE any controller or filter in the chain.
 * Strips dangerous HTML/JS from all incoming request bodies and parameters.
 *
 * Protects against:
 *   - Stored XSS  (<script>, onerror=, javascript:, etc.)
 *   - HTML injection (<iframe>, <img onerror=...>, etc.)
 *
 * Note: SQL injection is prevented by JPA parameterized queries (Hibernate).
 *       This filter is an additional defense-in-depth layer.
 */
@Component
@Order(1)
public class XssSanitizationFilter implements Filter {

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        chain.doFilter(new SanitizedRequestWrapper((HttpServletRequest) request), response);
    }

    // ─────────────────────────────────────────────────────────────
    //  Inner wrapper — wraps the real request with sanitized body
    // ─────────────────────────────────────────────────────────────
    static class SanitizedRequestWrapper extends HttpServletRequestWrapper {

        // Patterns ordered by risk severity
        private static final Pattern SCRIPT_TAG    = Pattern.compile(
                "(?i)<\\s*script[^>]*>[\\s\\S]*?<\\s*/\\s*script\\s*>", Pattern.DOTALL);
        private static final Pattern SCRIPT_SELF   = Pattern.compile(
                "(?i)<\\s*script[^>]*/?>", Pattern.DOTALL);
        private static final Pattern JS_PROTO      = Pattern.compile(
                "(?i)javascript\\s*:");
        private static final Pattern EVENT_HANDLER = Pattern.compile(
                "(?i)\\bon\\w{1,20}\\s*=\\s*[\"']?[^\"'\\s>]*");
        private static final Pattern IFRAME_TAG    = Pattern.compile(
                "(?i)<\\s*iframe[^>]*>[\\s\\S]*?<\\s*/\\s*iframe\\s*>", Pattern.DOTALL);
        private static final Pattern DATA_URL      = Pattern.compile(
                "(?i)data\\s*:\\s*text/html");

        private final byte[] sanitizedBody;

        SanitizedRequestWrapper(HttpServletRequest request) throws IOException {
            super(request);
            byte[] rawBytes = request.getInputStream().readAllBytes();
            String body = new String(rawBytes, StandardCharsets.UTF_8);
            this.sanitizedBody = sanitize(body).getBytes(StandardCharsets.UTF_8);
        }

        /** Strips dangerous patterns from a string */
        static String sanitize(String input) {
            if (input == null || input.isBlank()) return input;
            String s = input;
            s = SCRIPT_TAG.matcher(s).replaceAll("");
            s = SCRIPT_SELF.matcher(s).replaceAll("");
            s = IFRAME_TAG.matcher(s).replaceAll("");
            s = EVENT_HANDLER.matcher(s).replaceAll("");
            s = JS_PROTO.matcher(s).replaceAll("blocked:");
            s = DATA_URL.matcher(s).replaceAll("blocked:");
            return s;
        }

        @Override
        public ServletInputStream getInputStream() {
            final ByteArrayInputStream bais = new ByteArrayInputStream(sanitizedBody);
            return new ServletInputStream() {
                @Override public int read() { return bais.read(); }
                @Override public boolean isFinished() { return bais.available() == 0; }
                @Override public boolean isReady() { return true; }
                @Override public void setReadListener(ReadListener l) {}
            };
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(
                new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }

        /** Sanitize query parameters as well */
        @Override
        public String getParameter(String name) {
            String val = super.getParameter(name);
            return val != null ? sanitize(val) : null;
        }

        @Override
        public String[] getParameterValues(String name) {
            String[] vals = super.getParameterValues(name);
            if (vals == null) return null;
            return Arrays.stream(vals).map(SanitizedRequestWrapper::sanitize).toArray(String[]::new);
        }

        @Override
        public Map<String, String[]> getParameterMap() {
            Map<String, String[]> raw = super.getParameterMap();
            Map<String, String[]> clean = new LinkedHashMap<>();
            raw.forEach((k, v) -> clean.put(k,
                Arrays.stream(v).map(SanitizedRequestWrapper::sanitize).toArray(String[]::new)));
            return Collections.unmodifiableMap(clean);
        }
    }
}
