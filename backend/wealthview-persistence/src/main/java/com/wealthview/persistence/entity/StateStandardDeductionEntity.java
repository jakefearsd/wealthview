package com.wealthview.persistence.entity;

import java.math.BigDecimal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "state_standard_deductions")
public class StateStandardDeductionEntity extends UuidCreatedAtEntity {

    @Column(name = "state_code", nullable = false)
    private String stateCode;

    @Column(name = "tax_year", nullable = false)
    private int taxYear;

    @Column(name = "filing_status", nullable = false)
    private String filingStatus;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    protected StateStandardDeductionEntity() {
    }

    public StateStandardDeductionEntity(String stateCode, int taxYear, String filingStatus, BigDecimal amount) {
        this.stateCode = stateCode;
        this.taxYear = taxYear;
        this.filingStatus = filingStatus;
        this.amount = amount;
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

    public BigDecimal getAmount() {
        return amount;
    }

}
