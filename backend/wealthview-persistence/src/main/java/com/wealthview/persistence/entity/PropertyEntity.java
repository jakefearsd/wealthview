package com.wealthview.persistence.entity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.Filter;

@Entity
@Table(name = "properties")
// ExcessivePublicCount/GodClass: this is a JPA data-carrier facade — every accessor is a
// thin null-guarded delegate to the LoanDetails/DepreciationSettings embeddables, not real
// behavioral complexity. The delegation is required to preserve the public API for downstream modules.
@SuppressWarnings({"PMD.ExcessivePublicCount", "PMD.GodClass"})
@Filter(name = "tenantFilter", condition = "tenant_id = :tenantId")
public class PropertyEntity extends UuidAuditable {

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

    @Embedded
    @AttributeOverrides({
        @AttributeOverride(name = "loanAmount",
                column = @Column(name = "loan_amount", precision = 19, scale = 4)),
        @AttributeOverride(name = "annualInterestRate",
                column = @Column(name = "annual_interest_rate", precision = 7, scale = 5)),
        @AttributeOverride(name = "loanTermMonths",
                column = @Column(name = "loan_term_months")),
        @AttributeOverride(name = "loanStartDate",
                column = @Column(name = "loan_start_date")),
        @AttributeOverride(name = "useComputedBalance",
                column = @Column(name = "use_computed_balance", nullable = false))
    })
    private LoanDetails loanDetails = new LoanDetails();

    @Column(name = "property_type", nullable = false)
    private String propertyType = "primary_residence";

    @Column(name = "zillow_zpid")
    private String zillowZpid;

    @Column(name = "annual_appreciation_rate", precision = 7, scale = 5)
    private BigDecimal annualAppreciationRate;

    @Column(name = "annual_property_tax", precision = 19, scale = 4)
    private BigDecimal annualPropertyTax;

    @Column(name = "annual_insurance_cost", precision = 19, scale = 4)
    private BigDecimal annualInsuranceCost;

    @Column(name = "annual_maintenance_cost", precision = 19, scale = 4)
    private BigDecimal annualMaintenanceCost;

    // cost_seg_allocations keeps its @JdbcTypeCode/@Column on the embeddable field (jsonb
    // mappings cannot be expressed via @AttributeOverride); the other columns are overridden here.
    @Embedded
    @AttributeOverrides({
        @AttributeOverride(name = "inServiceDate",
                column = @Column(name = "in_service_date")),
        @AttributeOverride(name = "landValue",
                column = @Column(name = "land_value", precision = 19, scale = 4)),
        @AttributeOverride(name = "depreciationMethod",
                column = @Column(name = "depreciation_method", nullable = false)),
        @AttributeOverride(name = "usefulLifeYears",
                column = @Column(name = "useful_life_years", nullable = false, precision = 4, scale = 1)),
        @AttributeOverride(name = "bonusDepreciationRate",
                column = @Column(name = "bonus_depreciation_rate", nullable = false, precision = 5, scale = 4)),
        @AttributeOverride(name = "costSegStudyYear",
                column = @Column(name = "cost_seg_study_year"))
    })
    private DepreciationSettings depreciationSettings = new DepreciationSettings();

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

    public TenantEntity getTenant() {
        return tenant;
    }

    public UUID getTenantId() {
        return tenant.getId();
    }

    public String getAddress() {
        return address;
    }

    public void setAddress(String address) {
        this.address = address;
    }

    public BigDecimal getPurchasePrice() {
        return purchasePrice;
    }

    public void setPurchasePrice(BigDecimal purchasePrice) {
        this.purchasePrice = purchasePrice;
    }

    public LocalDate getPurchaseDate() {
        return purchaseDate;
    }

    public void setPurchaseDate(LocalDate purchaseDate) {
        this.purchaseDate = purchaseDate;
    }

    public BigDecimal getCurrentValue() {
        return currentValue;
    }

    public void setCurrentValue(BigDecimal currentValue) {
        this.currentValue = currentValue;
    }

    public BigDecimal getMortgageBalance() {
        return mortgageBalance;
    }

    public void setMortgageBalance(BigDecimal mortgageBalance) {
        this.mortgageBalance = mortgageBalance;
    }

    public BigDecimal getLoanAmount() {
        return loanDetails == null ? null : loanDetails.getLoanAmount();
    }

    public void setLoanAmount(BigDecimal loanAmount) {
        ensureLoanDetails().setLoanAmount(loanAmount);
    }

    public BigDecimal getAnnualInterestRate() {
        return loanDetails == null ? null : loanDetails.getAnnualInterestRate();
    }

    public void setAnnualInterestRate(BigDecimal annualInterestRate) {
        ensureLoanDetails().setAnnualInterestRate(annualInterestRate);
    }

    public Integer getLoanTermMonths() {
        return loanDetails == null ? null : loanDetails.getLoanTermMonths();
    }

    public void setLoanTermMonths(Integer loanTermMonths) {
        ensureLoanDetails().setLoanTermMonths(loanTermMonths);
    }

    public LocalDate getLoanStartDate() {
        return loanDetails == null ? null : loanDetails.getLoanStartDate();
    }

    public void setLoanStartDate(LocalDate loanStartDate) {
        ensureLoanDetails().setLoanStartDate(loanStartDate);
    }

    public boolean isUseComputedBalance() {
        return loanDetails != null && loanDetails.isUseComputedBalance();
    }

    public void setUseComputedBalance(boolean useComputedBalance) {
        ensureLoanDetails().setUseComputedBalance(useComputedBalance);
    }

    public String getPropertyType() {
        return propertyType;
    }

    public void setPropertyType(String propertyType) {
        this.propertyType = propertyType;
    }

    public String getZillowZpid() {
        return zillowZpid;
    }

    public void setZillowZpid(String zillowZpid) {
        this.zillowZpid = zillowZpid;
    }

    public BigDecimal getAnnualAppreciationRate() {
        return annualAppreciationRate;
    }

    public void setAnnualAppreciationRate(BigDecimal annualAppreciationRate) {
        this.annualAppreciationRate = annualAppreciationRate;
    }

    public BigDecimal getAnnualPropertyTax() {
        return annualPropertyTax;
    }

    public void setAnnualPropertyTax(BigDecimal annualPropertyTax) {
        this.annualPropertyTax = annualPropertyTax;
    }

    public BigDecimal getAnnualInsuranceCost() {
        return annualInsuranceCost;
    }

    public void setAnnualInsuranceCost(BigDecimal annualInsuranceCost) {
        this.annualInsuranceCost = annualInsuranceCost;
    }

    public BigDecimal getAnnualMaintenanceCost() {
        return annualMaintenanceCost;
    }

    public void setAnnualMaintenanceCost(BigDecimal annualMaintenanceCost) {
        this.annualMaintenanceCost = annualMaintenanceCost;
    }

    public LocalDate getInServiceDate() {
        return depreciationSettings == null ? null : depreciationSettings.getInServiceDate();
    }

    public void setInServiceDate(LocalDate inServiceDate) {
        ensureDepreciationSettings().setInServiceDate(inServiceDate);
    }

    public BigDecimal getLandValue() {
        return depreciationSettings == null ? null : depreciationSettings.getLandValue();
    }

    public void setLandValue(BigDecimal landValue) {
        ensureDepreciationSettings().setLandValue(landValue);
    }

    public String getDepreciationMethod() {
        return depreciationSettings == null ? null : depreciationSettings.getDepreciationMethod();
    }

    public void setDepreciationMethod(String depreciationMethod) {
        ensureDepreciationSettings().setDepreciationMethod(depreciationMethod);
    }

    public BigDecimal getUsefulLifeYears() {
        return depreciationSettings == null ? null : depreciationSettings.getUsefulLifeYears();
    }

    public void setUsefulLifeYears(BigDecimal usefulLifeYears) {
        ensureDepreciationSettings().setUsefulLifeYears(usefulLifeYears);
    }

    public String getCostSegAllocations() {
        return depreciationSettings == null ? null : depreciationSettings.getCostSegAllocations();
    }

    public void setCostSegAllocations(String costSegAllocations) {
        ensureDepreciationSettings().setCostSegAllocations(costSegAllocations);
    }

    public BigDecimal getBonusDepreciationRate() {
        return depreciationSettings == null ? null : depreciationSettings.getBonusDepreciationRate();
    }

    public void setBonusDepreciationRate(BigDecimal bonusDepreciationRate) {
        ensureDepreciationSettings().setBonusDepreciationRate(bonusDepreciationRate);
    }

    public Integer getCostSegStudyYear() {
        return depreciationSettings == null ? null : depreciationSettings.getCostSegStudyYear();
    }

    public void setCostSegStudyYear(Integer costSegStudyYear) {
        ensureDepreciationSettings().setCostSegStudyYear(costSegStudyYear);
    }

    public boolean hasLoanDetails() {
        return loanDetails != null && loanDetails.isComplete();
    }

    public BigDecimal getEquity() {
        return currentValue.subtract(mortgageBalance);
    }

    private LoanDetails ensureLoanDetails() {
        if (loanDetails == null) {
            loanDetails = new LoanDetails();
        }
        return loanDetails;
    }

    private DepreciationSettings ensureDepreciationSettings() {
        if (depreciationSettings == null) {
            depreciationSettings = new DepreciationSettings();
        }
        return depreciationSettings;
    }
}
