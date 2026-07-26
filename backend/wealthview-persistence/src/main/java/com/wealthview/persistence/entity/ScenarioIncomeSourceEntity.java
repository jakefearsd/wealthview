package com.wealthview.persistence.entity;

import java.math.BigDecimal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "scenario_income_sources")
public class ScenarioIncomeSourceEntity extends UuidCreatedAtEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "scenario_id", nullable = false)
    private ProjectionScenarioEntity scenario;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "income_source_id", nullable = false)
    private IncomeSourceEntity incomeSource;

    @Column(name = "override_annual_amount", precision = 19, scale = 4)
    private BigDecimal overrideAnnualAmount;

    protected ScenarioIncomeSourceEntity() {
    }

    public ScenarioIncomeSourceEntity(ProjectionScenarioEntity scenario,
                                      IncomeSourceEntity incomeSource,
                                      BigDecimal overrideAnnualAmount) {
        this.scenario = scenario;
        this.incomeSource = incomeSource;
        this.overrideAnnualAmount = overrideAnnualAmount;
    }

    public ProjectionScenarioEntity getScenario() {
        return scenario;
    }

    public void setScenario(ProjectionScenarioEntity scenario) {
        this.scenario = scenario;
    }

    public IncomeSourceEntity getIncomeSource() {
        return incomeSource;
    }

    public void setIncomeSource(IncomeSourceEntity incomeSource) {
        this.incomeSource = incomeSource;
    }

    public BigDecimal getOverrideAnnualAmount() {
        return overrideAnnualAmount;
    }

    public void setOverrideAnnualAmount(BigDecimal overrideAnnualAmount) {
        this.overrideAnnualAmount = overrideAnnualAmount;
    }

    /**
     * The annual amount this link actually contributes: the scenario-specific override
     * when the user set one, otherwise the linked income source's own annual amount.
     */
    public BigDecimal effectiveAnnualAmount() {
        return overrideAnnualAmount != null ? overrideAnnualAmount : incomeSource.getAnnualAmount();
    }

}
