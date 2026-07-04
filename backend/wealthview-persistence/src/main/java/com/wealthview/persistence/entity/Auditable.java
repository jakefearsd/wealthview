package com.wealthview.persistence.entity;

import java.time.OffsetDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * Base for mutable entities carrying both {@code created_at} and
 * {@code updated_at} audit stamps.
 *
 * <p>{@code updated_at} is maintained by Hibernate's {@link UpdateTimestamp} on
 * every flush of a dirty entity — services no longer set it by hand, so an
 * update path can never silently persist a stale timestamp. The field
 * initializer keeps the value non-null before the first flush for unit tests.
 */
@MappedSuperclass
public abstract class Auditable extends CreatedAtEntity {

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt = OffsetDateTime.now();

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }
}
