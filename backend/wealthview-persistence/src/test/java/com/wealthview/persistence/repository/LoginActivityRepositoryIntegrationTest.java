package com.wealthview.persistence.repository;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.wealthview.persistence.AbstractIntegrationTest;
import com.wealthview.persistence.entity.LoginActivityEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers the {@code UuidCreatedAtEntity} ladder via {@link LoginActivityEntity}: id
 * generation used to come from a bare {@code @GeneratedValue} directly on the entity
 * (normalized to the inherited {@code GenerationType.UUID} strategy when the id field
 * moved to the shared base class); this pins that saving still produces a non-null,
 * unique, random-looking UUID identical to every other UUID-keyed entity.
 */
class LoginActivityRepositoryIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private LoginActivityRepository loginActivityRepository;

    @Test
    void save_newLoginActivity_generatesNonNullUuidId() {
        var saved = loginActivityRepository.save(
                new LoginActivityEntity("user@example.com", UUID.randomUUID(), true, "127.0.0.1"));

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getCreatedAt()).isNotNull();
    }

    @Test
    void save_twoLoginActivities_generatesDistinctIds() {
        var first = loginActivityRepository.save(
                new LoginActivityEntity("first@example.com", null, true, "10.0.0.1"));
        var second = loginActivityRepository.save(
                new LoginActivityEntity("second@example.com", null, false, "10.0.0.2"));

        assertThat(first.getId()).isNotEqualTo(second.getId());
    }
}
