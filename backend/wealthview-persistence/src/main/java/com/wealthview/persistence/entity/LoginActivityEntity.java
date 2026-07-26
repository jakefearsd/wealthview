package com.wealthview.persistence.entity;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.Filter;

@Entity
@Table(name = "login_activity")
@Filter(name = "tenantFilter", condition = "tenant_id = :tenantId")
public class LoginActivityEntity extends UuidCreatedAtEntity {

    @Column(name = "user_email", nullable = false)
    private String userEmail;

    @Column(name = "tenant_id")
    private UUID tenantId;

    @Column(nullable = false)
    private boolean success;

    @Column(name = "ip_address")
    private String ipAddress;

    protected LoginActivityEntity() {
    }

    public LoginActivityEntity(String userEmail, UUID tenantId, boolean success, String ipAddress) {
        this.userEmail = userEmail;
        this.tenantId = tenantId;
        this.success = success;
        this.ipAddress = ipAddress;
    }

    public String getUserEmail() {
        return userEmail;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public boolean isSuccess() {
        return success;
    }

    public String getIpAddress() {
        return ipAddress;
    }

}
