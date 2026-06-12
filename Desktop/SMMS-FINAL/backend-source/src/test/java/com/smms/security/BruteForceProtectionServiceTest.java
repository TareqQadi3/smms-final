package com.smms.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for login brute-force protection.
 * Policy under test: 5 failed attempts → 15-minute lockout, per username.
 */
class BruteForceProtectionServiceTest {

    private BruteForceProtectionService service;

    @BeforeEach
    void setUp() {
        service = new BruteForceProtectionService();
    }

    @Test
    void freshUsernameIsNotBlocked() {
        assertThat(service.isBlocked("nobody")).isFalse();
        assertThat(service.attemptsRemaining("nobody")).isEqualTo(5);
    }

    @Test
    void fourFailuresDoNotBlock() {
        for (int i = 0; i < 4; i++) {
            assertThat(service.recordFailure("user1")).isFalse();
        }
        assertThat(service.isBlocked("user1")).isFalse();
        assertThat(service.attemptsRemaining("user1")).isEqualTo(1);
    }

    @Test
    void fifthFailureBlocksTheAccount() {
        for (int i = 0; i < 4; i++) service.recordFailure("user1");
        assertThat(service.recordFailure("user1")).isTrue();
        assertThat(service.isBlocked("user1")).isTrue();
        assertThat(service.attemptsRemaining("user1")).isZero();
    }

    @Test
    void successfulLoginResetsCounter() {
        for (int i = 0; i < 4; i++) service.recordFailure("user1");
        service.recordSuccess("user1");
        assertThat(service.isBlocked("user1")).isFalse();
        assertThat(service.attemptsRemaining("user1")).isEqualTo(5);
    }

    @Test
    @DisplayName("usernames are matched case-insensitively")
    void caseInsensitiveTracking() {
        for (int i = 0; i < 5; i++) service.recordFailure("Admin");
        assertThat(service.isBlocked("admin")).isTrue();
        assertThat(service.isBlocked("ADMIN")).isTrue();

        service.recordSuccess("ADMIN");
        assertThat(service.isBlocked("admin")).isFalse();
    }

    @Test
    void failuresAreTrackedPerUsername() {
        for (int i = 0; i < 5; i++) service.recordFailure("victim");
        assertThat(service.isBlocked("victim")).isTrue();
        assertThat(service.isBlocked("bystander")).isFalse();
        assertThat(service.attemptsRemaining("bystander")).isEqualTo(5);
    }

    @Test
    void blockPersistsOnFurtherFailures() {
        for (int i = 0; i < 6; i++) service.recordFailure("user1");
        assertThat(service.isBlocked("user1")).isTrue();
    }
}
