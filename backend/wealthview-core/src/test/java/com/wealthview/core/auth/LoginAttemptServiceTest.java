package com.wealthview.core.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LoginAttemptServiceTest {

    private LoginAttemptService service;

    @BeforeEach
    void setUp() {
        service = new LoginAttemptService();
    }

    @Test
    void isBlocked_noAttempts_returnsFalse() {
        assertThat(service.isBlocked("user@test.com")).isFalse();
    }

    @Test
    void isBlocked_underThreshold_returnsFalse() {
        for (int i = 0; i < 4; i++) {
            service.recordFailure("user@test.com");
        }
        assertThat(service.isBlocked("user@test.com")).isFalse();
    }

    @Test
    void isBlocked_atThreshold_returnsTrue() {
        for (int i = 0; i < 5; i++) {
            service.recordFailure("user@test.com");
        }
        assertThat(service.isBlocked("user@test.com")).isTrue();
    }

    @Test
    void recordSuccess_resetsCounter() {
        for (int i = 0; i < 5; i++) {
            service.recordFailure("user@test.com");
        }
        assertThat(service.isBlocked("user@test.com")).isTrue();

        service.recordSuccess("user@test.com");
        assertThat(service.isBlocked("user@test.com")).isFalse();
    }

    @Test
    void isBlocked_caseInsensitive() {
        for (int i = 0; i < 5; i++) {
            service.recordFailure("User@Test.COM");
        }
        assertThat(service.isBlocked("user@test.com")).isTrue();
    }

    @Test
    void isBlocked_differentEmails_independent() {
        for (int i = 0; i < 5; i++) {
            service.recordFailure("user1@test.com");
        }
        assertThat(service.isBlocked("user1@test.com")).isTrue();
        assertThat(service.isBlocked("user2@test.com")).isFalse();
    }
}
