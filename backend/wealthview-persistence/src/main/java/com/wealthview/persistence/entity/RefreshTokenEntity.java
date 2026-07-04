package com.wealthview.persistence.entity;

import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Filter;

@Entity
@Table(name = "refresh_tokens")
@Filter(name = "tenantFilter", condition = "tenant_id = :tenantId")
public class RefreshTokenEntity extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "session_id")
    private UUID sessionId;

    @Column(name = "jti", nullable = false, unique = true)
    private UUID jti;

    @Column(name = "issued_at", nullable = false)
    private OffsetDateTime issuedAt;

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    @Column(name = "used_at")
    private OffsetDateTime usedAt;

    @Column(name = "replaced_by_jti")
    private UUID replacedByJti;

    @Column(name = "revoked_at")
    private OffsetDateTime revokedAt;

    protected RefreshTokenEntity() {
    }

    public RefreshTokenEntity(UUID tenantId, UUID userId, UUID jti,
                              OffsetDateTime issuedAt, OffsetDateTime expiresAt) {
        this(tenantId, userId, null, jti, issuedAt, expiresAt);
    }

    public RefreshTokenEntity(UUID tenantId, UUID userId, UUID sessionId, UUID jti,
                              OffsetDateTime issuedAt, OffsetDateTime expiresAt) {
        this.tenantId = tenantId;
        this.userId = userId;
        this.sessionId = sessionId;
        this.jti = jti;
        this.issuedAt = issuedAt;
        this.expiresAt = expiresAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getSessionId() {
        return sessionId;
    }

    public UUID getJti() {
        return jti;
    }

    public OffsetDateTime getIssuedAt() {
        return issuedAt;
    }

    public OffsetDateTime getExpiresAt() {
        return expiresAt;
    }

    public OffsetDateTime getUsedAt() {
        return usedAt;
    }

    public void setUsedAt(OffsetDateTime usedAt) {
        this.usedAt = usedAt;
    }

    public UUID getReplacedByJti() {
        return replacedByJti;
    }

    public void setReplacedByJti(UUID replacedByJti) {
        this.replacedByJti = replacedByJti;
    }

    public OffsetDateTime getRevokedAt() {
        return revokedAt;
    }

    public void setRevokedAt(OffsetDateTime revokedAt) {
        this.revokedAt = revokedAt;
    }

}
