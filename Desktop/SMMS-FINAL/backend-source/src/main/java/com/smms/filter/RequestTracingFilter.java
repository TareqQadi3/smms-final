package com.smms.filter;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Assigns a unique request ID to every HTTP request and puts it in MDC
 * so all log statements within the request carry the trace ID automatically.
 *
 * Header: X-Request-ID (incoming) or auto-generated UUID.
 * Response header: X-Request-ID is echoed back to the caller.
 */
@Component
@Order(1)  // run first, before auth
public class RequestTracingFilter extends OncePerRequestFilter {

    public static final String REQUEST_ID_HEADER = "X-Request-ID";
    public static final String MDC_REQUEST_ID    = "requestId";
    public static final String MDC_TENANT_ID     = "tenantId";
    public static final String MDC_USER_ID       = "userId";

    @Override
    protected void doFilterInternal(HttpServletRequest req,
                                    HttpServletResponse res,
                                    FilterChain chain) throws ServletException, IOException {
        String requestId = req.getHeader(REQUEST_ID_HEADER);
        if (requestId == null || requestId.isBlank()) {
            requestId = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        }

        MDC.put(MDC_REQUEST_ID, requestId);
        res.setHeader(REQUEST_ID_HEADER, requestId);  // echo back to caller

        try {
            chain.doFilter(req, res);
        } finally {
            MDC.remove(MDC_REQUEST_ID);
            MDC.remove(MDC_TENANT_ID);
            MDC.remove(MDC_USER_ID);
        }
    }
}
