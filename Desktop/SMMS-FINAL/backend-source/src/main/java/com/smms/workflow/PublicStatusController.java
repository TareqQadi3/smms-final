package com.smms.workflow;

import com.smms.exception.GlobalExceptionHandler.ErrorBody;
import com.smms.workflow.WorkflowDtos.PublicStatusResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/**
 * Unauthenticated, token-based request status (SecurityConfig already permits GET /api/public/**).
 * Disabled unless {@code app.public-status.enabled=true}. Unknown, expired, revoked, malformed
 * tokens and the disabled state all give the same 404. Rate limited per client address
 * (X-Real-IP as set by nginx, else the remote address): 429 beyond the limit.
 */
@RestController
@RequestMapping("/api/public/request-status")
@RequiredArgsConstructor
public class PublicStatusController {

    private final StatusLinkService linkService;
    private final PublicStatusRateLimiter limiter;
    private final PublicStatusProperties props;

    @GetMapping("/{token}")
    public ResponseEntity<?> status(@PathVariable String token, HttpServletRequest request) {
        if (!props.isEnabled()) return notFound();
        if (!limiter.tryAcquire(clientKey(request))) {
            return hardened(ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).header("Retry-After", "60"))
                .body(new ErrorBody(429, "Too many requests", Instant.now()));
        }
        return linkService.lookup(token)
            .<ResponseEntity<?>>map(r -> hardened(ResponseEntity.ok()).body(r))
            .orElseGet(PublicStatusController::notFound);
    }

    private static ResponseEntity<?> notFound() {
        return hardened(ResponseEntity.status(HttpStatus.NOT_FOUND)).body(new ErrorBody(404, "Not found", Instant.now()));
    }

    private static ResponseEntity.BodyBuilder hardened(ResponseEntity.BodyBuilder b) {
        return b.cacheControl(CacheControl.noStore()).header("Referrer-Policy", "no-referrer")
            .header("X-Content-Type-Options", "nosniff");
    }

    static String clientKey(HttpServletRequest request) {
        String real = request.getHeader("X-Real-IP");
        if (real != null && !real.isBlank() && real.length() <= 64) return real.trim();
        return request.getRemoteAddr();
    }
}
