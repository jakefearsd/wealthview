package com.wealthview.persistence.entity;

import java.time.OffsetDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import org.hibernate.annotations.CreationTimestamp;

/**
 * Base for entities that carry a {@code created_at} stamp but are otherwise
 * append-only (audit rows, price points, login activity, reference tables).
 *
 * <p>The timestamp is populated by Hibernate's {@link CreationTimestamp} on
 * insert; the field initializer gives a sane value before persistence so unit
 * tests that never hit the database still read a non-null instant.
 */
// AbstractClassWithoutAbstractMethod: a JPA @MappedSuperclass shares column
// mappings with concrete subclasses and intentionally declares no abstract methods.
@SuppressWarnings("PMD.AbstractClassWithoutAbstractMethod")
@MappedSuperclass
public abstract class CreatedAtEntity {

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
