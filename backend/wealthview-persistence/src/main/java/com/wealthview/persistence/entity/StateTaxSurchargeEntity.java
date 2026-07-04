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
@Table(name = "state_tax_surcharges")
public class StateTaxSurchargeEntity extends CreatedAtEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "state_code", nullable = false)
    private String stateCode;

    @Column(name = "tax_year", nullable = false)
    private int taxYear;

    @Column(name = "filing_status", nullable = false)
    private String filingStatus;

    @Column(name = "surcharge_name", nullable = false)
    private String surchargeName;

    @Column(name = "income_threshold", nullable = false, precision = 19, scale = 4)
    private BigDecimal incomeThreshold;

    @Column(nullable = false, precision = 5, scale = 4)
    private BigDecimal rate;

    protected StateTaxSurchargeEntity() {
    }

    public StateTaxSurchargeEntity(String stateCode, int taxYear, String filingStatus,
                                    String surchargeName, BigDecimal incomeThreshold, BigDecimal rate) {
        this.stateCode = stateCode;
        this.taxYear = taxYear;
        this.filingStatus = filingStatus;
        this.surchargeName = surchargeName;
        this.incomeThreshold = incomeThreshold;
        this.rate = rate;
    }

    public UUID getId() {
        return id;
    }

    public String getStateCode() {
        return stateCode;
    }

    public int getTaxYear() {
        return taxYear;
    }

    public String getFilingStatus() {
        return filingStatus;
    }

    public String getSurchargeName() {
        return surchargeName;
    }

    public BigDecimal getIncomeThreshold() {
        return incomeThreshold;
    }

    public BigDecimal getRate() {
        return rate;
    }

}
