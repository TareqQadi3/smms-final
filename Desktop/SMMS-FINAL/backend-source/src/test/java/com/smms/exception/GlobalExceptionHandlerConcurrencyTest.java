package com.smms.exception;

import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import static org.assertj.core.api.Assertions.assertThat;

/** Lost updates and lock waits are retryable 409s with generic messages. */
class GlobalExceptionHandlerConcurrencyTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    private void assert409(ResponseEntity<GlobalExceptionHandler.ErrorBody> r, String... contains) {
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(r.getBody().status()).isEqualTo(409);
        for (String c : contains) assertThat(r.getBody().message()).containsIgnoringCase(c);
        // generic: no entity names, ids or SQL
        assertThat(r.getBody().message()).doesNotContain("WorkOrder").doesNotContain("SQL").doesNotContain("work_orders");
    }

    @Test
    void optimisticLockFailureIs409Reload() {
        assert409(handler.optimisticLock(new ObjectOptimisticLockingFailureException("com.smms.entity.WorkOrder", "id-1")),
            "concurrently", "reload");
    }

    @Test
    void jpaOptimisticLockExceptionIs409Reload() {
        assert409(handler.optimisticLock(new jakarta.persistence.OptimisticLockException("row 7 changed")), "reload");
    }

    @Test
    void cannotAcquireLockIs409Retry() {
        assert409(handler.lockBusy(new CannotAcquireLockException("could not obtain lock on work_orders")), "retry");
    }

    @Test
    void deadlockVictimIs409Retry() {
        assert409(handler.lockBusy(new DeadlockLoserDataAccessException("deadlock detected on SQL ...", null)), "retry");
    }

    @Test
    void jpaLockTimeoutAndPessimisticLockAre409Retry() {
        assert409(handler.lockBusy(new jakarta.persistence.LockTimeoutException("timeout")), "retry");
        assert409(handler.lockBusy(new jakarta.persistence.PessimisticLockException("locked")), "retry");
    }

    @Test
    void handlersAreRegisteredForTheRightTypes() throws Exception {
        var opt = GlobalExceptionHandler.class.getMethod("optimisticLock", Exception.class)
            .getAnnotation(org.springframework.web.bind.annotation.ExceptionHandler.class).value();
        var lock = GlobalExceptionHandler.class.getMethod("lockBusy", Exception.class)
            .getAnnotation(org.springframework.web.bind.annotation.ExceptionHandler.class).value();
        assertThat(opt).contains(org.springframework.dao.OptimisticLockingFailureException.class,
            jakarta.persistence.OptimisticLockException.class);
        assertThat(lock).contains(org.springframework.dao.PessimisticLockingFailureException.class,
            jakarta.persistence.PessimisticLockException.class, jakarta.persistence.LockTimeoutException.class);
        assertThat(org.springframework.dao.PessimisticLockingFailureException.class)
            .isAssignableFrom(CannotAcquireLockException.class);
    }
}
