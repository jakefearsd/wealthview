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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "properties")
@SuppressWarnings({"PMD.TooManyFields", "PMD.ExcessivePublicCount"})
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

    @Column(name = "property_type", nullable = false)
    private String propertyType = "primary_residence";

    @Column(name = "zillow_zpid")
    private String zillowZpid;

    @Column(name = "in_service_date")
    private LocalDate inServiceDate;

    @Column(name = "land_value", precision = 19, scale = 4)
    private BigDecimal landValue;

    @Column(name = "annual_appreciation_rate", precision = 7, scale = 5)
    private BigDecimal annualAppreciationRate;

    @Column(name = "annual_property_tax", precision = 19, scale = 4)
    private BigDecimal annualPropertyTax;

    @Column(name = "annual_insurance_cost", precision = 19, scale = 4)
    private BigDecimal annualInsuranceCost;

    @Column(name = "annual_maintenance_cost", precision = 19, scale = 4)
    private BigDecimal annualMaintenanceCost;

    @Column(name = "depreciation_method", nullable = false)
    private String depreciationMethod = "none";

    @Column(name = "useful_life_years", nullable = false, precision = 4, scale = 1)
    private BigDecimal usefulLifeYears = new BigDecimal("27.5");

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "cost_seg_allocations", nullable = false, columnDefinition = "jsonb")
    private String costSegAllocations = "[]";

    @Column(name = "bonus_depreciation_rate", nullable = false, precision = 5, scale = 4)
    private BigDecimal bonusDepreciationRate = BigDecimal.ONE;

    @Column(name = "cost_seg_study_year")
    private Integer costSegStudyYear;

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
    public String getPropertyType() { return propertyType; }
    public void setPropertyType(String propertyType) { this.propertyType = propertyType; }

    public String getZillowZpid() { return zillowZpid; }
    public void setZillowZpid(String zillowZpid) { this.zillowZpid = zillowZpid; }

    public BigDecimal getAnnualAppreciationRate() { return annualAppreciationRate; }
    public void setAnnualAppreciationRate(BigDecimal annualAppreciationRate) { this.annualAppreciationRate = annualAppreciationRate; }
    public BigDecimal getAnnualPropertyTax() { return annualPropertyTax; }
    public void setAnnualPropertyTax(BigDecimal annualPropertyTax) { this.annualPropertyTax = annualPropertyTax; }
    public BigDecimal getAnnualInsuranceCost() { return annualInsuranceCost; }
    public void setAnnualInsuranceCost(BigDecimal annualInsuranceCost) { this.annualInsuranceCost = annualInsuranceCost; }
    public BigDecimal getAnnualMaintenanceCost() { return annualMaintenanceCost; }
    public void setAnnualMaintenanceCost(BigDecimal annualMaintenanceCost) { this.annualMaintenanceCost = annualMaintenanceCost; }

    public LocalDate getInServiceDate() { return inServiceDate; }
    public void setInServiceDate(LocalDate inServiceDate) { this.inServiceDate = inServiceDate; }
    public BigDecimal getLandValue() { return landValue; }
    public void setLandValue(BigDecimal landValue) { this.landValue = landValue; }
    public String getDepreciationMethod() { return depreciationMethod; }
    public void setDepreciationMethod(String depreciationMethod) { this.depreciationMethod = depreciationMethod; }
    public BigDecimal getUsefulLifeYears() { return usefulLifeYears; }
    public void setUsefulLifeYears(BigDecimal usefulLifeYears) { this.usefulLifeYears = usefulLifeYears; }

    public String getCostSegAllocations() { return costSegAllocations; }
    public void setCostSegAllocations(String costSegAllocations) { this.costSegAllocations = costSegAllocations; }
    public BigDecimal getBonusDepreciationRate() { return bonusDepreciationRate; }
    public void setBonusDepreciationRate(BigDecimal bonusDepreciationRate) { this.bonusDepreciationRate = bonusDepreciationRate; }
    public Integer getCostSegStudyYear() { return costSegStudyYear; }
    public void setCostSegStudyYear(Integer costSegStudyYear) { this.costSegStudyYear = costSegStudyYear; }

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
