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
@Table(name = "user_sessions")
@Filter(name = "tenantFilter", condition = "tenant_id = :tenantId")
public class UserSessionEntity extends CreatedAtEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "device_label")
    private String deviceLabel;

    @Column(name = "transport", nullable = false)
    private String transport;

    @Column(name = "ip_address")
    private String ipAddress;

    @Column(name = "user_agent")
    private String userAgent;

    @Column(name = "last_used_at", nullable = false)
    private OffsetDateTime lastUsedAt = OffsetDateTime.now();

    @Column(name = "revoked_at")
    private OffsetDateTime revokedAt;

    protected UserSessionEntity() {
    }

    public UserSessionEntity(UUID tenantId, UUID userId, String deviceLabel,
                             String transport, String ipAddress, String userAgent) {
        this.tenantId = tenantId;
        this.userId = userId;
        this.deviceLabel = deviceLabel;
        this.transport = transport;
        this.ipAddress = ipAddress;
        this.userAgent = userAgent;
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

    public String getDeviceLabel() {
        return deviceLabel;
    }

    public String getTransport() {
        return transport;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public OffsetDateTime getLastUsedAt() {
        return lastUsedAt;
    }

    public void setLastUsedAt(OffsetDateTime lastUsedAt) {
        this.lastUsedAt = lastUsedAt;
    }

    public OffsetDateTime getRevokedAt() {
        return revokedAt;
    }

    public void setRevokedAt(OffsetDateTime revokedAt) {
        this.revokedAt = revokedAt;
    }
}
