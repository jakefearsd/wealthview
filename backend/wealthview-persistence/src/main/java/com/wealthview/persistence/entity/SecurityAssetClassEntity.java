package com.wealthview.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "security_asset_class")
public class SecurityAssetClassEntity extends UuidAuditable {

    @Column(name = "symbol", nullable = false)
    private String symbol;

    @Column(name = "asset_class", nullable = false)
    private String assetClass;

    protected SecurityAssetClassEntity() {
    }

    public SecurityAssetClassEntity(String symbol, String assetClass) {
        this.symbol = symbol;
        this.assetClass = assetClass;
    }

    public String getSymbol() {
        return symbol;
    }

    public String getAssetClass() {
        return assetClass;
    }
}
