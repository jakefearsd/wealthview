package com.wealthview.persistence.entity;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "security_class_override")
public class SecurityClassOverrideEntity extends UuidAuditable {

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "symbol", nullable = false)
    private String symbol;

    @Column(name = "asset_class", nullable = false)
    private String assetClass;

    protected SecurityClassOverrideEntity() {
    }

    public SecurityClassOverrideEntity(UUID tenantId, String symbol, String assetClass) {
        this.tenantId = tenantId;
        this.symbol = symbol;
        this.assetClass = assetClass;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public String getSymbol() {
        return symbol;
    }

    public String getAssetClass() {
        return assetClass;
    }

    public void setAssetClass(String assetClass) {
        this.assetClass = assetClass;
    }
}
