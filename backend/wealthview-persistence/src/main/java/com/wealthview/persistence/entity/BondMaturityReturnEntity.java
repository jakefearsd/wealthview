package com.wealthview.persistence.entity;

import java.math.BigDecimal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "bond_maturity_returns")
public class BondMaturityReturnEntity extends UuidAuditable {

    @Column(name = "year", nullable = false)
    private int year;

    @Column(name = "maturity", nullable = false)
    private String maturity;

    @Column(name = "real_return", nullable = false, precision = 9, scale = 6)
    private BigDecimal realReturn;

    protected BondMaturityReturnEntity() {
    }

    public BondMaturityReturnEntity(int year, String maturity, BigDecimal realReturn) {
        this.year = year;
        this.maturity = maturity;
        this.realReturn = realReturn;
    }

    public int getYear() {
        return year;
    }

    public String getMaturity() {
        return maturity;
    }

    public BigDecimal getRealReturn() {
        return realReturn;
    }
}
