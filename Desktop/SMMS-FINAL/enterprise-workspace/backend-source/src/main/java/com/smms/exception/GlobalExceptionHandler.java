package com.smms.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.stream.Collectors;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ErrorBody> notFound(ResourceNotFoundException e) {
        log.warn("Resource not found: {}", e.getMessage());
        return error(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(WorkflowException.class)
    public ResponseEntity<ErrorBody> workflow(WorkflowException e) {
        log.warn("Workflow error: {}", e.getMessage());
        return error(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
    }

    @ExceptionHandler(AuthException.class)
    public ResponseEntity<ErrorBody> auth(AuthException e) {
        log.warn("Auth error: {}", e.getMessage());
        return error(HttpStatus.UNAUTHORIZED, e.getMessage());
    }

    @ExceptionHandler(TicketLockedException.class)
    public ResponseEntity<ErrorBody> locked(TicketLockedException e) {
        log.warn("Ticket locked: {}", e.getMessage());
        return error(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorBody> access(AccessDeniedException e) {
        log.warn("Access denied: {}", e.getMessage());
        return error(HttpStatus.FORBIDDEN, "Access denied — insufficient permissions");
    }

    // Fix #8: Handle IllegalArgumentException (duplicate username/email, role hierarchy violations)
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorBody> illegalArg(IllegalArgumentException e) {
        log.warn("Validation error: {}", e.getMessage());
        return error(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorBody> validation(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
            .map(f -> f.getField() + ": " + f.getDefaultMessage())
            .collect(Collectors.joining("; "));
        log.warn("Request validation failed: {}", msg);
        return error(HttpStatus.BAD_REQUEST, msg);
    }

    // Fix #14: Log all unexpected exceptions before returning 500
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorBody> generic(Exception e) {
        log.error("Unexpected error: {}", e.getMessage(), e);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error — please contact support");
    }

    private ResponseEntity<ErrorBody> error(HttpStatus status, String message) {
        return ResponseEntity.status(status)
            .body(new ErrorBody(status.value(), message, Instant.now()));
    }

    public record ErrorBody(int status, String message, Instant timestamp) {}
}
