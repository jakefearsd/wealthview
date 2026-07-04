package com.wealthview.persistence.entity;

import java.math.BigDecimal;
import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Value object bundling the depreciation / cost-segregation attributes of a property.
 *
 * <p>Column mappings for the scalar fields are supplied by the owning {@link PropertyEntity}
 * via {@code @AttributeOverride}. The {@code cost_seg_allocations} jsonb column keeps its
 * {@link JdbcTypeCode}/{@link Column} mapping on the field because those cannot be expressed
 * through an attribute override. The physical schema is unchanged.
 */
@Embeddable
public class DepreciationSettings {

    private LocalDate inServiceDate;

    private BigDecimal landValue;

    private String depreciationMethod = "none";

    private BigDecimal usefulLifeYears = new BigDecimal("27.5");

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "cost_seg_allocations", nullable = false, columnDefinition = "jsonb")
    private String costSegAllocations = "[]";

    private BigDecimal bonusDepreciationRate = BigDecimal.ONE;

    private Integer costSegStudyYear;

    public LocalDate getInServiceDate() {
        return inServiceDate;
    }

    public void setInServiceDate(LocalDate inServiceDate) {
        this.inServiceDate = inServiceDate;
    }

    public BigDecimal getLandValue() {
        return landValue;
    }

    public void setLandValue(BigDecimal landValue) {
        this.landValue = landValue;
    }

    public String getDepreciationMethod() {
        return depreciationMethod;
    }

    public void setDepreciationMethod(String depreciationMethod) {
        this.depreciationMethod = depreciationMethod;
    }

    public BigDecimal getUsefulLifeYears() {
        return usefulLifeYears;
    }

    public void setUsefulLifeYears(BigDecimal usefulLifeYears) {
        this.usefulLifeYears = usefulLifeYears;
    }

    public String getCostSegAllocations() {
        return costSegAllocations;
    }

    public void setCostSegAllocations(String costSegAllocations) {
        this.costSegAllocations = costSegAllocations;
    }

    public BigDecimal getBonusDepreciationRate() {
        return bonusDepreciationRate;
    }

    public void setBonusDepreciationRate(BigDecimal bonusDepreciationRate) {
        this.bonusDepreciationRate = bonusDepreciationRate;
    }

    public Integer getCostSegStudyYear() {
        return costSegStudyYear;
    }

    public void setCostSegStudyYear(Integer costSegStudyYear) {
        this.costSegStudyYear = costSegStudyYear;
    }
}
