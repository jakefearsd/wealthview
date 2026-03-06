package com.wealthview.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "properties")
public class PropertyEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id", nullable = false)
    private TenantEntity tenant;

    @Column(nullable = false)
    private String address;

    @Column(name = "purchase_price", nullable = false, precision = 19, scale = 4)
    private BigDecimal purchasePrice;

    @Column(name = "purchase_date", nullable = false)
    private LocalDate purchaseDate;

    @Column(name = "current_value", nullable = false, precision = 19, scale = 4)
    private BigDecimal currentValue;

    @Column(name = "mortgage_balance", nullable = false, precision = 19, scale = 4)
    private BigDecimal mortgageBalance = BigDecimal.ZERO;

    @Column(name = "loan_amount", precision = 19, scale = 4)
    private BigDecimal loanAmount;

    @Column(name = "annual_interest_rate", precision = 7, scale = 5)
    private BigDecimal annualInterestRate;

    @Column(name = "loan_term_months")
    private Integer loanTermMonths;

    @Column(name = "loan_start_date")
    private LocalDate loanStartDate;

    @Column(name = "use_computed_balance", nullable = false)
    private boolean useComputedBalance = false;

    // TODO: Future depreciation fields
    // private String depreciationMethod;
    // private BigDecimal depreciableBasis;
    // private LocalDate placedInServiceDate;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt = OffsetDateTime.now();

    protected PropertyEntity() {
    }

    public PropertyEntity(TenantEntity tenant, String address, BigDecimal purchasePrice,
                          LocalDate purchaseDate, BigDecimal currentValue, BigDecimal mortgageBalance) {
        this.tenant = tenant;
        this.address = address;
        this.purchasePrice = purchasePrice;
        this.purchaseDate = purchaseDate;
        this.currentValue = currentValue;
        this.mortgageBalance = mortgageBalance;
    }

    public UUID getId() { return id; }
    public TenantEntity getTenant() { return tenant; }
    public UUID getTenantId() { return tenant.getId(); }
    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }
    public BigDecimal getPurchasePrice() { return purchasePrice; }
    public void setPurchasePrice(BigDecimal purchasePrice) { this.purchasePrice = purchasePrice; }
    public LocalDate getPurchaseDate() { return purchaseDate; }
    public void setPurchaseDate(LocalDate purchaseDate) { this.purchaseDate = purchaseDate; }
    public BigDecimal getCurrentValue() { return currentValue; }
    public void setCurrentValue(BigDecimal currentValue) { this.currentValue = currentValue; }
    public BigDecimal getMortgageBalance() { return mortgageBalance; }
    public void setMortgageBalance(BigDecimal mortgageBalance) { this.mortgageBalance = mortgageBalance; }
    public BigDecimal getLoanAmount() { return loanAmount; }
    public void setLoanAmount(BigDecimal loanAmount) { this.loanAmount = loanAmount; }
    public BigDecimal getAnnualInterestRate() { return annualInterestRate; }
    public void setAnnualInterestRate(BigDecimal annualInterestRate) { this.annualInterestRate = annualInterestRate; }
    public Integer getLoanTermMonths() { return loanTermMonths; }
    public void setLoanTermMonths(Integer loanTermMonths) { this.loanTermMonths = loanTermMonths; }
    public LocalDate getLoanStartDate() { return loanStartDate; }
    public void setLoanStartDate(LocalDate loanStartDate) { this.loanStartDate = loanStartDate; }
    public boolean isUseComputedBalance() { return useComputedBalance; }
    public void setUseComputedBalance(boolean useComputedBalance) { this.useComputedBalance = useComputedBalance; }

    public boolean hasLoanDetails() {
        return loanAmount != null && annualInterestRate != null
                && loanTermMonths != null && loanStartDate != null;
    }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }

    public BigDecimal getEquity() {
        return currentValue.subtract(mortgageBalance);
    }
}
