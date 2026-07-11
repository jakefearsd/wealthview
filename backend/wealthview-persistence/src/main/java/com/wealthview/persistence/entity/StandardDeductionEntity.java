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
@Table(name = "standard_deductions")
public class StandardDeductionEntity extends CreatedAtEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tax_year", nullable = false)
    private int taxYear;

    @Column(name = "filing_status", nullable = false)
    private String filingStatus;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    /**
     * The IRS age-65+ additional standard deduction (Pub. 501), per qualifying person. Defaults to
     * zero for rows that predate this feature; see {@code V074__add_age65_deduction_to_standard_
     * deductions.sql}.
     */
    @Column(name = "additional_age65", nullable = false, precision = 19, scale = 4)
    private BigDecimal additionalAge65 = BigDecimal.ZERO;

    protected StandardDeductionEntity() {
    }

    public StandardDeductionEntity(int taxYear, String filingStatus, BigDecimal amount) {
        this(taxYear, filingStatus, amount, BigDecimal.ZERO);
    }

    public StandardDeductionEntity(int taxYear, String filingStatus, BigDecimal amount,
                                    BigDecimal additionalAge65) {
        this.taxYear = taxYear;
        this.filingStatus = filingStatus;
        this.amount = amount;
        this.additionalAge65 = additionalAge65 != null ? additionalAge65 : BigDecimal.ZERO;
    }

    public UUID getId() {
        return id;
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

    public BigDecimal getAdditionalAge65() {
        return additionalAge65;
    }

}
