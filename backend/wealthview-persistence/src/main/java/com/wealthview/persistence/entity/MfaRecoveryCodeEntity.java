package com.wealthview.persistence.entity;

import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.Filter;

@Entity
@Table(name = "mfa_recovery_codes")
@Filter(name = "tenantFilter", condition = "tenant_id = :tenantId")
public class MfaRecoveryCodeEntity extends UuidAuditable {

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "code_hash", nullable = false)
    private String codeHash;

    @Column(name = "used_at")
    private OffsetDateTime usedAt;

    protected MfaRecoveryCodeEntity() {
    }

    public MfaRecoveryCodeEntity(UUID tenantId, UUID userId, String codeHash) {
        this.tenantId = tenantId;
        this.userId = userId;
        this.codeHash = codeHash;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getCodeHash() {
        return codeHash;
    }

    public OffsetDateTime getUsedAt() {
        return usedAt;
    }

    public void setUsedAt(OffsetDateTime usedAt) {
        this.usedAt = usedAt;
    }

}
