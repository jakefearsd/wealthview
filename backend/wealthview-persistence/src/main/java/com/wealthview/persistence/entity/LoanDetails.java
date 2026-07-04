package com.wealthview.persistence.entity;

import java.math.BigDecimal;
import java.time.LocalDate;

import jakarta.persistence.Embeddable;

/**
 * Value object bundling the mortgage/loan attributes of a property.
 *
 * <p>Column mappings (names, precision, scale, nullability) are supplied by the owning
 * {@link PropertyEntity} via {@code @AttributeOverride}, so the physical schema is unchanged.
 */
@Embeddable
public class LoanDetails {

    private BigDecimal loanAmount;

    private BigDecimal annualInterestRate;

    private Integer loanTermMonths;

    private LocalDate loanStartDate;

    private boolean useComputedBalance;

    public BigDecimal getLoanAmount() {
        return loanAmount;
    }

    public void setLoanAmount(BigDecimal loanAmount) {
        this.loanAmount = loanAmount;
    }

    public BigDecimal getAnnualInterestRate() {
        return annualInterestRate;
    }

    public void setAnnualInterestRate(BigDecimal annualInterestRate) {
        this.annualInterestRate = annualInterestRate;
    }

    public Integer getLoanTermMonths() {
        return loanTermMonths;
    }

    public void setLoanTermMonths(Integer loanTermMonths) {
        this.loanTermMonths = loanTermMonths;
    }

    public LocalDate getLoanStartDate() {
        return loanStartDate;
    }

    public void setLoanStartDate(LocalDate loanStartDate) {
        this.loanStartDate = loanStartDate;
    }

    public boolean isUseComputedBalance() {
        return useComputedBalance;
    }

    public void setUseComputedBalance(boolean useComputedBalance) {
        this.useComputedBalance = useComputedBalance;
    }

    /**
     * True when every field required to amortize the loan is present.
     */
    public boolean isComplete() {
        return loanAmount != null && annualInterestRate != null
                && loanTermMonths != null && loanStartDate != null;
    }
}
