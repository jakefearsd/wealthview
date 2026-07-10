package com.wealthview.persistence.entity;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "security_asset_class")
public class SecurityAssetClassEntity extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

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

    public UUID getId() {
        return id;
    }

    public String getSymbol() {
        return symbol;
    }

    public String getAssetClass() {
        return assetClass;
    }
}
