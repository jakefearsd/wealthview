package com.wealthview.persistence.entity;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "projection_scenarios")
public class ProjectionScenarioEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id", nullable = false)
    private TenantEntity tenant;

    @Column(nullable = false)
    private String name;

    @Column(name = "retirement_date")
    private LocalDate retirementDate;

    @Column(name = "end_age")
    private Integer endAge;

    @Column(name = "inflation_rate", precision = 5, scale = 4)
    private BigDecimal inflationRate;

    @Column(name = "params_json", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String paramsJson;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "spending_profile_id")
    private SpendingProfileEntity spendingProfile;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "guardrail_profile_id")
    private GuardrailSpendingProfileEntity guardrailProfile;

    @OneToMany(mappedBy = "scenario", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<ProjectionAccountEntity> accounts = new ArrayList<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt = OffsetDateTime.now();

    protected ProjectionScenarioEntity() {
    }

    public ProjectionScenarioEntity(TenantEntity tenant, String name, LocalDate retirementDate,
                                     Integer endAge, BigDecimal inflationRate, String paramsJson) {
        this.tenant = tenant;
        this.name = name;
        this.retirementDate = retirementDate;
        this.endAge = endAge;
        this.inflationRate = inflationRate;
        this.paramsJson = paramsJson;
    }

    public UUID getId() { return id; }
    public TenantEntity getTenant() { return tenant; }
    public UUID getTenantId() { return tenant.getId(); }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public LocalDate getRetirementDate() { return retirementDate; }
    public void setRetirementDate(LocalDate retirementDate) { this.retirementDate = retirementDate; }
    public Integer getEndAge() { return endAge; }
    public void setEndAge(Integer endAge) { this.endAge = endAge; }
    public BigDecimal getInflationRate() { return inflationRate; }
    public void setInflationRate(BigDecimal inflationRate) { this.inflationRate = inflationRate; }
    public String getParamsJson() { return paramsJson; }
    public void setParamsJson(String paramsJson) { this.paramsJson = paramsJson; }
    public SpendingProfileEntity getSpendingProfile() { return spendingProfile; }
    public void setSpendingProfile(SpendingProfileEntity spendingProfile) { this.spendingProfile = spendingProfile; }
    public GuardrailSpendingProfileEntity getGuardrailProfile() { return guardrailProfile; }
    public void setGuardrailProfile(GuardrailSpendingProfileEntity guardrailProfile) { this.guardrailProfile = guardrailProfile; }
    public List<ProjectionAccountEntity> getAccounts() { return accounts; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }

    public void addAccount(ProjectionAccountEntity account) {
        accounts.add(account);
        account.setScenario(this);
    }
}
