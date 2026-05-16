package com.wealthview.persistence.entity;

import java.time.OffsetDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Mobile-app version-check row.
 *
 * <p>Global table — NOT scoped to a tenant. Deliberately no
 * {@code @Filter("tenantFilter")} so a tenant-context-less request (the
 * version-check endpoint is anonymous) can read it without tripping the
 * Hibernate filter assertion. If a future reader is tempted to add the
 * filter for symmetry: don't. The table holds one row per platform and is
 * shared across the entire deployment.
 */
@Entity
@Table(name = "mobile_app_versions")
public class MobileAppVersionEntity {

    @Id
    @Column(nullable = false)
    private String platform;

    @Column(name = "minimum_supported_version", nullable = false)
    private String minimumSupportedVersion;

    @Column(name = "latest_version", nullable = false)
    private String latestVersion;

    @Column(name = "store_url", nullable = false)
    private String storeUrl;

    @Column(name = "message")
    private String message;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt = OffsetDateTime.now();

    protected MobileAppVersionEntity() {
    }

    public MobileAppVersionEntity(String platform, String minimumSupportedVersion,
                                  String latestVersion, String storeUrl, String message) {
        this.platform = platform;
        this.minimumSupportedVersion = minimumSupportedVersion;
        this.latestVersion = latestVersion;
        this.storeUrl = storeUrl;
        this.message = message;
    }

    public String getPlatform() {
        return platform;
    }

    public String getMinimumSupportedVersion() {
        return minimumSupportedVersion;
    }

    public void setMinimumSupportedVersion(String minimumSupportedVersion) {
        this.minimumSupportedVersion = minimumSupportedVersion;
    }

    public String getLatestVersion() {
        return latestVersion;
    }

    public void setLatestVersion(String latestVersion) {
        this.latestVersion = latestVersion;
    }

    public String getStoreUrl() {
        return storeUrl;
    }

    public void setStoreUrl(String storeUrl) {
        this.storeUrl = storeUrl;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(OffsetDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
