package com.wealthview.persistence.entity;

import java.math.BigDecimal;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "spending_profiles")
@Filter(name = "tenantFilter", condition = "tenant_id = :tenantId")
public class SpendingProfileEntity extends UuidAuditable {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id", nullable = false)
    private TenantEntity tenant;

    @Column(nullable = false)
    private String name;

    @Column(name = "essential_expenses", nullable = false, precision = 19, scale = 4)
    private BigDecimal essentialExpenses = BigDecimal.ZERO;

    @Column(name = "discretionary_expenses", nullable = false, precision = 19, scale = 4)
    private BigDecimal discretionaryExpenses = BigDecimal.ZERO;

    @Column(name = "income_streams", columnDefinition = "jsonb", nullable = false)
    @JdbcTypeCode(SqlTypes.JSON)
    private String incomeStreams = "[]";

    @Column(name = "spending_tiers", columnDefinition = "jsonb", nullable = false)
    @JdbcTypeCode(SqlTypes.JSON)
    private String spendingTiers = "[]";

    protected SpendingProfileEntity() {
    }

    public SpendingProfileEntity(TenantEntity tenant, String name,
                                  BigDecimal essentialExpenses, BigDecimal discretionaryExpenses,
                                  String spendingTiers) {
        this.tenant = tenant;
        this.name = name;
        this.essentialExpenses = essentialExpenses;
        this.discretionaryExpenses = discretionaryExpenses;
        this.spendingTiers = spendingTiers != null ? spendingTiers : "[]";
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

    public BigDecimal getEssentialExpenses() {
        return essentialExpenses;
    }

    public void setEssentialExpenses(BigDecimal essentialExpenses) {
        this.essentialExpenses = essentialExpenses;
    }

    public BigDecimal getDiscretionaryExpenses() {
        return discretionaryExpenses;
    }

    public void setDiscretionaryExpenses(BigDecimal discretionaryExpenses) {
        this.discretionaryExpenses = discretionaryExpenses;
    }

    public String getIncomeStreams() {
        return incomeStreams;
    }

    public String getSpendingTiers() {
        return spendingTiers;
    }

    public void setSpendingTiers(String spendingTiers) {
        this.spendingTiers = spendingTiers;
    }

}
