package com.smms.exception;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the global exception handler.
 * Verifies correct HTTP status codes and that no stack traces leak.
 */
class GlobalExceptionHandlerTest {

    private GlobalExceptionHandler handler;

    @BeforeEach
    void setUp() {
        handler = new GlobalExceptionHandler();
    }

    @Test
    void resourceNotFoundReturns404() {
        ResponseEntity<GlobalExceptionHandler.ErrorBody> resp =
            handler.notFound(new ResourceNotFoundException("Work order not found"));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().message()).contains("Work order not found");
        assertThat(resp.getBody().status()).isEqualTo(404);
    }

    @Test
    void workflowExceptionReturns422() {
        ResponseEntity<GlobalExceptionHandler.ErrorBody> resp =
            handler.workflow(new WorkflowException("Transition not allowed"));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(resp.getBody().status()).isEqualTo(422);
    }

    @Test
    void authExceptionReturns401() {
        ResponseEntity<GlobalExceptionHandler.ErrorBody> resp =
            handler.auth(new AuthException("Bad credentials"));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void ticketLockedReturns409() {
        ResponseEntity<GlobalExceptionHandler.ErrorBody> resp =
            handler.locked(new TicketLockedException("Ticket is locked by another user"));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void planLimitReturns402() {
        ResponseEntity<GlobalExceptionHandler.ErrorBody> resp =
            handler.planLimit(new PlanLimitExceededException("User limit reached (10)"));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.PAYMENT_REQUIRED);
        assertThat(resp.getBody().message()).contains("User limit reached");
    }

    @Test
    @DisplayName("suspended tenant → 403 Forbidden")
    void suspendedTenantReturns403() {
        TenantSuspendedException ex = new TenantSuspendedException("Tenant suspended", false);
        ResponseEntity<GlobalExceptionHandler.ErrorBody> resp = handler.tenantSuspended(ex);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("expired tenant → 402 Payment Required")
    void expiredTenantReturns402() {
        TenantSuspendedException ex = new TenantSuspendedException("Subscription expired", true);
        ResponseEntity<GlobalExceptionHandler.ErrorBody> resp = handler.tenantSuspended(ex);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.PAYMENT_REQUIRED);
    }

    @Test
    void accessDeniedReturns403WithSafeMessage() {
        ResponseEntity<GlobalExceptionHandler.ErrorBody> resp =
            handler.access(new AccessDeniedException("denied"));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(resp.getBody().message())
            .isEqualTo("Access denied — insufficient permissions"); // fixed safe message, not the raw exception
    }

    @Test
    void illegalArgumentReturns400() {
        ResponseEntity<GlobalExceptionHandler.ErrorBody> resp =
            handler.illegalArg(new IllegalArgumentException("Invalid input"));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void genericExceptionReturns500WithoutLeakingDetails() {
        ResponseEntity<GlobalExceptionHandler.ErrorBody> resp =
            handler.generic(new RuntimeException("Database password is secret"));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(resp.getBody().message())
            .doesNotContain("Database password")
            .contains("Internal server error");
    }

    @Test
    void errorBodyContainsTimestamp() {
        ResponseEntity<GlobalExceptionHandler.ErrorBody> resp =
            handler.notFound(new ResourceNotFoundException("x"));
        assertThat(resp.getBody().timestamp()).isNotNull();
    }

    @Test
    void dataIntegrityViolationIs409WithoutConstraintDetails() {
        var r = new GlobalExceptionHandler().integrity(
            new org.springframework.dao.DataIntegrityViolationException("duplicate key uq_users_username_lower"));
        org.assertj.core.api.Assertions.assertThat(r.getStatusCode().value()).isEqualTo(409);
        org.assertj.core.api.Assertions.assertThat(r.getBody().message()).doesNotContain("uq_users");
    }
}
