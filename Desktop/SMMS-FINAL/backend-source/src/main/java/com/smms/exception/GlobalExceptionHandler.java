package com.smms.exception;

import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.time.Instant;
import java.util.stream.Collectors;

/**
 * SECURITY LAYER — Centralized Exception Handler
 *
 * Guarantees:
 *   - NO internal stack traces leak to API consumers
 *   - Every error type maps to the correct HTTP status code
 *   - BUG-2 FIX: invalid UUID path variable → 400 (was 500)
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    // ── Domain exceptions ────────────────────────────────────

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ErrorBody> notFound(ResourceNotFoundException e) {
        log.warn("Not found: {}", e.getMessage());
        return error(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(WorkflowException.class)
    public ResponseEntity<ErrorBody> workflow(WorkflowException e) {
        log.warn("Workflow: {}", e.getMessage());
        return error(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
    }

    @ExceptionHandler(AuthException.class)
    public ResponseEntity<ErrorBody> auth(AuthException e) {
        log.warn("Auth: {}", e.getMessage());
        return error(HttpStatus.UNAUTHORIZED, e.getMessage());
    }

    @ExceptionHandler(AccountLockedException.class)
    public ResponseEntity<ErrorBody> accountLocked(AccountLockedException e) {
        log.warn("Account locked: {}", e.getMessage());
        return error(HttpStatus.TOO_MANY_REQUESTS, e.getMessage());
    }

    @ExceptionHandler(TicketLockedException.class)
    public ResponseEntity<ErrorBody> locked(TicketLockedException e) {
        log.warn("Locked: {}", e.getMessage());
        return error(HttpStatus.CONFLICT, e.getMessage());
    }

    // ── SaaS Tenant Lifecycle ─────────────────────────────────

    @ExceptionHandler(TenantSuspendedException.class)
    public ResponseEntity<ErrorBody> tenantSuspended(TenantSuspendedException e) {
        log.warn("Tenant blocked: {}", e.getMessage());
        HttpStatus status = e.isExpired() ? HttpStatus.PAYMENT_REQUIRED : HttpStatus.FORBIDDEN;
        return error(status, e.getMessage());
    }

    @ExceptionHandler(PlanLimitExceededException.class)
    public ResponseEntity<ErrorBody> planLimit(PlanLimitExceededException e) {
        log.warn("Plan limit: {}", e.getMessage());
        return error(HttpStatus.PAYMENT_REQUIRED, e.getMessage());
    }

    // ── Authorization ────────────────────────────────────────

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorBody> access(AccessDeniedException e) {
        log.warn("Access denied: {}", e.getMessage());
        return error(HttpStatus.FORBIDDEN, "Access denied — insufficient permissions");
    }

    // ── Validation / Bad Request ──────────────────────────────

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorBody> illegalArg(IllegalArgumentException e) {
        log.warn("Validation: {}", e.getMessage());
        return error(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorBody> validation(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
            .map(f -> f.getField() + ": " + f.getDefaultMessage())
            .collect(Collectors.joining("; "));
        log.warn("Field validation: {}", msg);
        return error(HttpStatus.BAD_REQUEST, msg);
    }

    /**
     * BUG-2 FIX: invalid UUID in path/query param → 400 (was 500 via generic handler)
     * Example: GET /api/users/not-a-uuid
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorBody> typeMismatch(MethodArgumentTypeMismatchException e) {
        String msg = "Invalid value for parameter '" + e.getName() + "'";
        if (e.getRequiredType() != null
                && e.getRequiredType().getSimpleName().equalsIgnoreCase("UUID")) {
            msg = "Invalid UUID format for parameter '" + e.getName() + "'";
        }
        log.warn("Type mismatch: {} — value={}", msg, e.getValue());
        return error(HttpStatus.BAD_REQUEST, msg);
    }

    /** Catches @Pattern / @Size violations on @RequestParam / @PathVariable */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorBody> constraintViolation(ConstraintViolationException e) {
        String msg = e.getConstraintViolations().stream()
            .map(v -> v.getPropertyPath().toString().replaceAll(".*\\.", "") + ": " + v.getMessage())
            .collect(Collectors.joining("; "));
        log.warn("Constraint violation: {}", msg);
        return error(HttpStatus.BAD_REQUEST, msg);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorBody> missingParam(MissingServletRequestParameterException e) {
        log.warn("Missing param: {}", e.getMessage());
        return error(HttpStatus.BAD_REQUEST, "Required parameter missing: " + e.getParameterName());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorBody> unreadable(HttpMessageNotReadableException e) {
        String msg;
        if (e.getMessage() != null && e.getMessage().contains("UUID")) {
            msg = "Invalid UUID format in request body";
        } else if (e.getMessage() != null && e.getMessage().contains("Enum")) {
            msg = "Invalid enum value — check allowed values";
        } else {
            msg = "Invalid request body — check field types and format";
        }
        log.warn("Unreadable request: {}", e.getMessage());
        return error(HttpStatus.BAD_REQUEST, msg);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorBody> noResource(NoResourceFoundException e) {
        return error(HttpStatus.NOT_FOUND, "Endpoint not found");
    }

    // ── Catch-all — NEVER exposes stack trace ────────────────

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorBody> generic(Exception e) {
        // Log full trace server-side but return safe generic message
        log.error("Unhandled exception [{}]: {}", e.getClass().getSimpleName(), e.getMessage(), e);
        return error(HttpStatus.INTERNAL_SERVER_ERROR,
            "Internal server error — please contact support");
    }

    // ── Helper ────────────────────────────────────────────────

    private ResponseEntity<ErrorBody> error(HttpStatus status, String message) {
        return ResponseEntity.status(status)
            .body(new ErrorBody(status.value(), message, Instant.now()));
    }

    public record ErrorBody(int status, String message, Instant timestamp) {}
}
