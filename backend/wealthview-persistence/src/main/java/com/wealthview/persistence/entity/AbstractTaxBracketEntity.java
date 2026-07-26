package com.wealthview.persistence.entity;

import java.math.BigDecimal;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;

@MappedSuperclass
public abstract class AbstractTaxBracketEntity extends UuidCreatedAtEntity {

    @Column(name = "tax_year", nullable = false)
    private int taxYear;

    @Column(name = "filing_status", nullable = false)
    private String filingStatus;

    @Column(name = "bracket_floor", nullable = false, precision = 19, scale = 4)
    private BigDecimal bracketFloor;

    @Column(name = "bracket_ceiling", precision = 19, scale = 4)
    private BigDecimal bracketCeiling;

    @Column(nullable = false, precision = 5, scale = 4)
    private BigDecimal rate;

    protected AbstractTaxBracketEntity() {
    }

    protected AbstractTaxBracketEntity(int taxYear, String filingStatus, BigDecimal bracketFloor,
                                        BigDecimal bracketCeiling, BigDecimal rate) {
        this.taxYear = taxYear;
        this.filingStatus = filingStatus;
        this.bracketFloor = bracketFloor;
        this.bracketCeiling = bracketCeiling;
        this.rate = rate;
    }

    public int getTaxYear() {
        return taxYear;
    }

    public String getFilingStatus() {
        return filingStatus;
    }

    public BigDecimal getBracketFloor() {
        return bracketFloor;
    }

    public BigDecimal getBracketCeiling() {
        return bracketCeiling;
    }

    public BigDecimal getRate() {
        return rate;
    }

}
