package com.smms.security;

import jakarta.servlet.ServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/** Regression tests for SEC-009: multipart must pass through untouched, JSON still sanitized. */
class XssSanitizationFilterTest {

    private final XssSanitizationFilter filter = new XssSanitizationFilter();

    @Test
    void multipartRequestPassesThroughUnwrapped() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/work-orders/1/attachments");
        req.setContentType("multipart/form-data; boundary=xyz");
        req.setContent("<script>alert(1)</script>".getBytes(StandardCharsets.UTF_8));
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(req, new MockHttpServletResponse(), chain);

        ServletRequest passed = chain.getRequest();
        assertThat(passed).isSameAs(req);
        assertThat(new String(passed.getInputStream().readAllBytes(), StandardCharsets.UTF_8))
            .isEqualTo("<script>alert(1)</script>");
    }

    @Test
    void jsonBodyIsStillSanitized() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/work-orders");
        req.setContentType("application/json");
        req.setContent("{\"title\":\"a<script>alert(1)</script>b\"}".getBytes(StandardCharsets.UTF_8));
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(req, new MockHttpServletResponse(), chain);

        ServletRequest passed = chain.getRequest();
        assertThat(passed).isNotSameAs(req);
        assertThat(new String(passed.getInputStream().readAllBytes(), StandardCharsets.UTF_8))
            .isEqualTo("{\"title\":\"ab\"}");
    }
}
