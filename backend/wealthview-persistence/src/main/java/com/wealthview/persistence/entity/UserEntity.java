package com.wealthview.persistence.entity;

import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.Filter;

@Entity
@Table(name = "users")
@Filter(name = "tenantFilter", condition = "tenant_id = :tenantId")
public class UserEntity extends UuidAuditable {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id", nullable = false)
    private TenantEntity tenant;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(nullable = false)
    private String role;

    @Column(name = "is_super_admin", nullable = false)
    private boolean isSuperAdmin = false;

    @Column(name = "is_active", nullable = false)
    private boolean isActive = true;

    @Column(name = "token_generation", nullable = false)
    private int tokenGeneration = 0;

    @Column(name = "mfa_enabled", nullable = false)
    private boolean mfaEnabled = false;

    @Column(name = "mfa_secret_encrypted")
    private String mfaSecretEncrypted;

    @Column(name = "mfa_setup_at")
    private OffsetDateTime mfaSetupAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected UserEntity() {
    }

    public UserEntity(TenantEntity tenant, String email, String passwordHash, String role) {
        this.tenant = tenant;
        this.email = email;
        this.passwordHash = passwordHash;
        this.role = role;
    }

    public TenantEntity getTenant() {
        return tenant;
    }

    public UUID getTenantId() {
        return tenant.getId();
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public boolean isSuperAdmin() {
        return isSuperAdmin;
    }

    public void setSuperAdmin(boolean superAdmin) {
        isSuperAdmin = superAdmin;
    }

    public boolean isActive() {
        return isActive;
    }

    public void setActive(boolean active) {
        isActive = active;
    }

    public int getTokenGeneration() {
        return tokenGeneration;
    }

    public void setTokenGeneration(int tokenGeneration) {
        this.tokenGeneration = tokenGeneration;
    }

    public long getVersion() {
        return version;
    }

    public boolean isMfaEnabled() {
        return mfaEnabled;
    }

    public void setMfaEnabled(boolean mfaEnabled) {
        this.mfaEnabled = mfaEnabled;
    }

    public String getMfaSecretEncrypted() {
        return mfaSecretEncrypted;
    }

    public void setMfaSecretEncrypted(String mfaSecretEncrypted) {
        this.mfaSecretEncrypted = mfaSecretEncrypted;
    }

    public OffsetDateTime getMfaSetupAt() {
        return mfaSetupAt;
    }

    public void setMfaSetupAt(OffsetDateTime mfaSetupAt) {
        this.mfaSetupAt = mfaSetupAt;
    }
}
