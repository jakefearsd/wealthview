package com.wealthview.persistence.entity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A retirement projection scenario. Carries at most ONE active spending plan at a time:
 * {@link #spendingProfile} (tier-based, user-defined) and {@link #guardrailProfile}
 * (Monte-Carlo-optimized) are mutually exclusive FKs — activating one clears the other. This
 * mirrors the {@code SpendingPlan} sealed interface in {@code wealthview-core}, which resolves
 * whichever one is set. Callers must use the tell-don't-ask mutators below
 * ({@link #activateSpendingProfile}, {@link #activateGuardrailProfile},
 * {@link #clearSpendingProfile}, {@link #clearGuardrailProfile}) rather than pairing the raw
 * setters, so the invariant can't be violated by a caller forgetting the paired null-out.
 */
@Entity
@Table(name = "projection_scenarios")
@Filter(name = "tenantFilter", condition = "tenant_id = :tenantId")
public class ProjectionScenarioEntity extends Auditable {

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

    // Hibernate can't order a @OneToMany bag by a generated UUID at the SQL level in a meaningful
    // way, but @OrderBy still forces a stable ORDER BY id on every fetch, so iteration order (and
    // anything derived from it, e.g. GuardrailProfileService's scenario signature) is reproducible
    // across runs instead of depending on incidental DB/JPA ordering.
    @OneToMany(mappedBy = "scenario", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id")
    private List<ProjectionAccountEntity> accounts = new ArrayList<>();

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

    public UUID getId() {
        return id;
    }

    public TenantEntity getTenant() {
        return tenant;
    }

    public UUID getTenantId() {
        return tenant.getId();
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public LocalDate getRetirementDate() {
        return retirementDate;
    }

    public void setRetirementDate(LocalDate retirementDate) {
        this.retirementDate = retirementDate;
    }

    public Integer getEndAge() {
        return endAge;
    }

    public void setEndAge(Integer endAge) {
        this.endAge = endAge;
    }

    public BigDecimal getInflationRate() {
        return inflationRate;
    }

    public void setInflationRate(BigDecimal inflationRate) {
        this.inflationRate = inflationRate;
    }

    public String getParamsJson() {
        return paramsJson;
    }

    public void setParamsJson(String paramsJson) {
        this.paramsJson = paramsJson;
    }

    public SpendingProfileEntity getSpendingProfile() {
        return spendingProfile;
    }

    /**
     * Raw setter retained for JPA-adjacent callers that only ever touch this one field (e.g.
     * clearing the FK when the referenced {@code SpendingProfileEntity} itself is deleted) and
     * for test fixture setup. Prefer {@link #activateSpendingProfile} or
     * {@link #clearSpendingProfile} at any call site that also needs to reason about
     * {@link #guardrailProfile} — see the class Javadoc for the XOR invariant.
     */
    public void setSpendingProfile(SpendingProfileEntity spendingProfile) {
        this.spendingProfile = spendingProfile;
    }

    public GuardrailSpendingProfileEntity getGuardrailProfile() {
        return guardrailProfile;
    }

    /**
     * Raw setter retained for test fixture setup. Prefer {@link #activateGuardrailProfile} or
     * {@link #clearGuardrailProfile} at any call site that also needs to reason about
     * {@link #spendingProfile} — see the class Javadoc for the XOR invariant.
     */
    public void setGuardrailProfile(GuardrailSpendingProfileEntity guardrailProfile) {
        this.guardrailProfile = guardrailProfile;
    }

    /**
     * Activates {@code spendingProfile} as this scenario's spending plan, clearing any
     * previously active guardrail profile so the XOR invariant (class Javadoc) always holds.
     * {@code spendingProfile} may be {@code null} (e.g. an id that failed to resolve) — the
     * guardrail is still cleared, matching this scenario switching away from "guardrail active".
     */
    public void activateSpendingProfile(SpendingProfileEntity spendingProfile) {
        this.spendingProfile = spendingProfile;
        clearGuardrailProfile();
    }

    /**
     * Activates {@code guardrailProfile} as this scenario's spending plan, clearing any
     * previously active (tier-based) spending profile so the XOR invariant (class Javadoc)
     * always holds.
     */
    public void activateGuardrailProfile(GuardrailSpendingProfileEntity guardrailProfile) {
        this.guardrailProfile = guardrailProfile;
        clearSpendingProfile();
    }

    /**
     * Clears the spending profile only. Leaves {@link #guardrailProfile} untouched — used when
     * a scenario edit deselects a tier-based plan without the edit form having any say over a
     * guardrail profile, which is managed exclusively by the optimizer.
     */
    // NullAssignment: an explicit null-out is the intended behavior for a "clear this optional
    // FK" mutator (JPA has no other vocabulary for "no relationship") -- not an accidental
    // null assignment PMD's smell detector is meant to catch.
    @SuppressWarnings("PMD.NullAssignment")
    public void clearSpendingProfile() {
        this.spendingProfile = null;
    }

    /**
     * Clears the guardrail profile only. Leaves {@link #spendingProfile} untouched.
     */
    // NullAssignment: same rationale as clearSpendingProfile above.
    @SuppressWarnings("PMD.NullAssignment")
    public void clearGuardrailProfile() {
        this.guardrailProfile = null;
    }

    public List<ProjectionAccountEntity> getAccounts() {
        return accounts;
    }

    public void addAccount(ProjectionAccountEntity account) {
        accounts.add(account);
        account.setScenario(this);
    }
}
