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
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "income_sources")
public class IncomeSourceEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id", nullable = false)
    private TenantEntity tenant;

    @Column(nullable = false)
    private String name;

    @Column(name = "income_type", nullable = false)
    private String incomeType;

    @Column(name = "annual_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal annualAmount;

    @Column(name = "start_age", nullable = false)
    private int startAge;

    @Column(name = "end_age")
    private Integer endAge;

    @Column(name = "inflation_rate", nullable = false, precision = 7, scale = 5)
    private BigDecimal inflationRate = BigDecimal.ZERO;

    @Column(name = "one_time", nullable = false)
    private boolean oneTime = false;

    @Column(name = "tax_treatment", nullable = false)
    private String taxTreatment = "taxable";

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "property_id")
    private PropertyEntity property;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt = OffsetDateTime.now();

    protected IncomeSourceEntity() {
    }

    public IncomeSourceEntity(TenantEntity tenant, String name, String incomeType,
                              BigDecimal annualAmount, int startAge, Integer endAge,
                              BigDecimal inflationRate, boolean oneTime, String taxTreatment) {
        this.tenant = tenant;
        this.name = name;
        this.incomeType = incomeType;
        this.annualAmount = annualAmount;
        this.startAge = startAge;
        this.endAge = endAge;
        this.inflationRate = inflationRate;
        this.oneTime = oneTime;
        this.taxTreatment = taxTreatment;
    }

    public UUID getId() { return id; }
    public TenantEntity getTenant() { return tenant; }
    public UUID getTenantId() { return tenant.getId(); }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getIncomeType() { return incomeType; }
    public void setIncomeType(String incomeType) { this.incomeType = incomeType; }
    public BigDecimal getAnnualAmount() { return annualAmount; }
    public void setAnnualAmount(BigDecimal annualAmount) { this.annualAmount = annualAmount; }
    public int getStartAge() { return startAge; }
    public void setStartAge(int startAge) { this.startAge = startAge; }
    public Integer getEndAge() { return endAge; }
    public void setEndAge(Integer endAge) { this.endAge = endAge; }
    public BigDecimal getInflationRate() { return inflationRate; }
    public void setInflationRate(BigDecimal inflationRate) { this.inflationRate = inflationRate; }
    public boolean isOneTime() { return oneTime; }
    public void setOneTime(boolean oneTime) { this.oneTime = oneTime; }
    public String getTaxTreatment() { return taxTreatment; }
    public void setTaxTreatment(String taxTreatment) { this.taxTreatment = taxTreatment; }
    public PropertyEntity getProperty() { return property; }
    public void setProperty(PropertyEntity property) { this.property = property; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
