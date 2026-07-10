package com.wealthview.persistence.entity;

import java.math.BigDecimal;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "asset_class_returns")
public class AssetClassReturnEntity extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "year", nullable = false)
    private int year;

    @Column(name = "asset_class", nullable = false)
    private String assetClass;

    @Column(name = "real_return", nullable = false, precision = 9, scale = 6)
    private BigDecimal realReturn;

    protected AssetClassReturnEntity() {
    }

    public AssetClassReturnEntity(int year, String assetClass, BigDecimal realReturn) {
        this.year = year;
        this.assetClass = assetClass;
        this.realReturn = realReturn;
    }

    public UUID getId() {
        return id;
    }

    public int getYear() {
        return year;
    }

    public String getAssetClass() {
        return assetClass;
    }

    public BigDecimal getRealReturn() {
        return realReturn;
    }
}
