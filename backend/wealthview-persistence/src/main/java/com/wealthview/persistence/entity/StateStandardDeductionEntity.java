package com.wealthview.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "state_standard_deductions")
public class StateStandardDeductionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "state_code", nullable = false)
    private String stateCode;

    @Column(name = "tax_year", nullable = false)
    private int taxYear;

    @Column(name = "filing_status", nullable = false)
    private String filingStatus;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    protected StateStandardDeductionEntity() {
    }

    public StateStandardDeductionEntity(String stateCode, int taxYear, String filingStatus, BigDecimal amount) {
        this.stateCode = stateCode;
        this.taxYear = taxYear;
        this.filingStatus = filingStatus;
        this.amount = amount;
    }

    public UUID getId() { return id; }
    public String getStateCode() { return stateCode; }
    public int getTaxYear() { return taxYear; }
    public String getFilingStatus() { return filingStatus; }
    public BigDecimal getAmount() { return amount; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
