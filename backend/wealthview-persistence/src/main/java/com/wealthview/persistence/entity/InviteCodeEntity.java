package com.wealthview.persistence.entity;

import java.time.OffsetDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.Filter;

@Entity
@Table(name = "invite_codes")
@Filter(name = "tenantFilter", condition = "tenant_id = :tenantId")
public class InviteCodeEntity extends UuidAuditable {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id", nullable = false)
    private TenantEntity tenant;

    @Column(nullable = false, unique = true)
    private String code;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by", nullable = false)
    private UserEntity createdBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "consumed_by")
    private UserEntity consumedBy;

    @Column(name = "consumed_at")
    private OffsetDateTime consumedAt;

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    @Column(name = "is_revoked", nullable = false)
    private boolean isRevoked = false;

    protected InviteCodeEntity() {
    }

    public InviteCodeEntity(TenantEntity tenant, String code, UserEntity createdBy, OffsetDateTime expiresAt) {
        this.tenant = tenant;
        this.code = code;
        this.createdBy = createdBy;
        this.expiresAt = expiresAt;
    }

    public TenantEntity getTenant() {
        return tenant;
    }

    public String getCode() {
        return code;
    }

    public UserEntity getCreatedBy() {
        return createdBy;
    }

    public UserEntity getConsumedBy() {
        return consumedBy;
    }

    public void setConsumedBy(UserEntity consumedBy) {
        this.consumedBy = consumedBy;
    }

    public OffsetDateTime getConsumedAt() {
        return consumedAt;
    }

    public void setConsumedAt(OffsetDateTime consumedAt) {
        this.consumedAt = consumedAt;
    }

    public OffsetDateTime getExpiresAt() {
        return expiresAt;
    }

    public boolean isRevoked() {
        return isRevoked;
    }

    public void setRevoked(boolean revoked) {
        isRevoked = revoked;
    }

    public boolean isConsumed() {
        return consumedBy != null;
    }

    public boolean isExpired() {
        return OffsetDateTime.now().isAfter(expiresAt);
    }
}
