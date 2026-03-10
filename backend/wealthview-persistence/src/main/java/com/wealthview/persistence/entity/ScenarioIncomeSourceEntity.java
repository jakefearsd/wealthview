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
@Table(name = "scenario_income_sources")
public class ScenarioIncomeSourceEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "scenario_id", nullable = false)
    private ProjectionScenarioEntity scenario;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "income_source_id", nullable = false)
    private IncomeSourceEntity incomeSource;

    @Column(name = "override_annual_amount", precision = 19, scale = 4)
    private BigDecimal overrideAnnualAmount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    protected ScenarioIncomeSourceEntity() {
    }

    public ScenarioIncomeSourceEntity(ProjectionScenarioEntity scenario,
                                      IncomeSourceEntity incomeSource,
                                      BigDecimal overrideAnnualAmount) {
        this.scenario = scenario;
        this.incomeSource = incomeSource;
        this.overrideAnnualAmount = overrideAnnualAmount;
    }

    public UUID getId() { return id; }
    public ProjectionScenarioEntity getScenario() { return scenario; }
    public void setScenario(ProjectionScenarioEntity scenario) { this.scenario = scenario; }
    public IncomeSourceEntity getIncomeSource() { return incomeSource; }
    public void setIncomeSource(IncomeSourceEntity incomeSource) { this.incomeSource = incomeSource; }
    public BigDecimal getOverrideAnnualAmount() { return overrideAnnualAmount; }
    public void setOverrideAnnualAmount(BigDecimal overrideAnnualAmount) { this.overrideAnnualAmount = overrideAnnualAmount; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
