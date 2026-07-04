package com.wealthview.persistence.entity;

import java.time.OffsetDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * Key/value system configuration. The {@code system_config} table carries only
 * {@code updated_at} (no {@code created_at}), so this is the lone entity that
 * can't extend {@link Auditable}; it keeps its own {@link UpdateTimestamp}-managed
 * column instead.
 */
@Entity
@Table(name = "system_config")
public class SystemConfigEntity {

    @Id
    private String key;

    @Column(nullable = false)
    private String value;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt = OffsetDateTime.now();

    protected SystemConfigEntity() {
    }

    public SystemConfigEntity(String key, String value) {
        this.key = key;
        this.value = value;
    }

    public String getKey() {
        return key;
    }

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }
}
