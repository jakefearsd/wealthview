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
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "guardrail_spending_profiles")
public class GuardrailSpendingProfileEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id", nullable = false)
    private TenantEntity tenant;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "scenario_id", nullable = false)
    private ProjectionScenarioEntity scenario;

    @Column(nullable = false)
    private String name;

    @Column(name = "essential_floor", nullable = false, precision = 19, scale = 4)
    private BigDecimal essentialFloor;

    @Column(name = "terminal_balance_target", nullable = false, precision = 19, scale = 4)
    private BigDecimal terminalBalanceTarget = BigDecimal.ZERO;

    @Column(name = "return_mean", nullable = false, precision = 7, scale = 4)
    private BigDecimal returnMean = new BigDecimal("0.10");

    @Column(name = "return_stddev", nullable = false, precision = 7, scale = 4)
    private BigDecimal returnStddev = new BigDecimal("0.15");

    @Column(name = "trial_count", nullable = false)
    private int trialCount = 5000;

    @Column(name = "confidence_level", nullable = false, precision = 5, scale = 4)
    private BigDecimal confidenceLevel = new BigDecimal("0.95");

    @Column(name = "phases", columnDefinition = "jsonb", nullable = false)
    @JdbcTypeCode(SqlTypes.JSON)
    private String phases = "[]";

    @Column(name = "yearly_spending", columnDefinition = "jsonb", nullable = false)
    @JdbcTypeCode(SqlTypes.JSON)
    private String yearlySpending = "[]";

    @Column(name = "median_final_balance", precision = 19, scale = 4)
    private BigDecimal medianFinalBalance;

    @Column(name = "failure_rate", precision = 7, scale = 4)
    private BigDecimal failureRate;

    @Column(name = "percentile_10_final", precision = 19, scale = 4)
    private BigDecimal percentile10Final;

    @Column(name = "percentile_90_final", precision = 19, scale = 4)
    private BigDecimal percentile90Final;

    @Column(name = "scenario_hash", nullable = false)
    private String scenarioHash;

    @Column(name = "is_stale", nullable = false)
    private boolean stale = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt = OffsetDateTime.now();

    protected GuardrailSpendingProfileEntity() {
    }

    public GuardrailSpendingProfileEntity(TenantEntity tenant, ProjectionScenarioEntity scenario,
                                           String name, BigDecimal essentialFloor) {
        this.tenant = tenant;
        this.scenario = scenario;
        this.name = name;
        this.essentialFloor = essentialFloor;
    }

    public UUID getId() { return id; }
    public TenantEntity getTenant() { return tenant; }
    public ProjectionScenarioEntity getScenario() { return scenario; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public BigDecimal getEssentialFloor() { return essentialFloor; }
    public void setEssentialFloor(BigDecimal essentialFloor) { this.essentialFloor = essentialFloor; }
    public BigDecimal getTerminalBalanceTarget() { return terminalBalanceTarget; }
    public void setTerminalBalanceTarget(BigDecimal terminalBalanceTarget) { this.terminalBalanceTarget = terminalBalanceTarget; }
    public BigDecimal getReturnMean() { return returnMean; }
    public void setReturnMean(BigDecimal returnMean) { this.returnMean = returnMean; }
    public BigDecimal getReturnStddev() { return returnStddev; }
    public void setReturnStddev(BigDecimal returnStddev) { this.returnStddev = returnStddev; }
    public int getTrialCount() { return trialCount; }
    public void setTrialCount(int trialCount) { this.trialCount = trialCount; }
    public BigDecimal getConfidenceLevel() { return confidenceLevel; }
    public void setConfidenceLevel(BigDecimal confidenceLevel) { this.confidenceLevel = confidenceLevel; }
    public String getPhases() { return phases; }
    public void setPhases(String phases) { this.phases = phases; }
    public String getYearlySpending() { return yearlySpending; }
    public void setYearlySpending(String yearlySpending) { this.yearlySpending = yearlySpending; }
    public BigDecimal getMedianFinalBalance() { return medianFinalBalance; }
    public void setMedianFinalBalance(BigDecimal medianFinalBalance) { this.medianFinalBalance = medianFinalBalance; }
    public BigDecimal getFailureRate() { return failureRate; }
    public void setFailureRate(BigDecimal failureRate) { this.failureRate = failureRate; }
    public BigDecimal getPercentile10Final() { return percentile10Final; }
    public void setPercentile10Final(BigDecimal percentile10Final) { this.percentile10Final = percentile10Final; }
    public BigDecimal getPercentile90Final() { return percentile90Final; }
    public void setPercentile90Final(BigDecimal percentile90Final) { this.percentile90Final = percentile90Final; }
    public String getScenarioHash() { return scenarioHash; }
    public void setScenarioHash(String scenarioHash) { this.scenarioHash = scenarioHash; }
    public boolean isStale() { return stale; }
    public void setStale(boolean stale) { this.stale = stale; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
