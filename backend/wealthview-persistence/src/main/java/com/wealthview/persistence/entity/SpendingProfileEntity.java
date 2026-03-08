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
@Table(name = "spending_profiles")
public class SpendingProfileEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

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

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt = OffsetDateTime.now();

    protected SpendingProfileEntity() {
    }

    public SpendingProfileEntity(TenantEntity tenant, String name,
                                  BigDecimal essentialExpenses, BigDecimal discretionaryExpenses,
                                  String incomeStreams, String spendingTiers) {
        this.tenant = tenant;
        this.name = name;
        this.essentialExpenses = essentialExpenses;
        this.discretionaryExpenses = discretionaryExpenses;
        this.incomeStreams = incomeStreams != null ? incomeStreams : "[]";
        this.spendingTiers = spendingTiers != null ? spendingTiers : "[]";
    }

    public UUID getId() { return id; }
    public TenantEntity getTenant() { return tenant; }
    public UUID getTenantId() { return tenant.getId(); }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public BigDecimal getEssentialExpenses() { return essentialExpenses; }
    public void setEssentialExpenses(BigDecimal essentialExpenses) { this.essentialExpenses = essentialExpenses; }
    public BigDecimal getDiscretionaryExpenses() { return discretionaryExpenses; }
    public void setDiscretionaryExpenses(BigDecimal discretionaryExpenses) { this.discretionaryExpenses = discretionaryExpenses; }
    public String getIncomeStreams() { return incomeStreams; }
    public void setIncomeStreams(String incomeStreams) { this.incomeStreams = incomeStreams; }
    public String getSpendingTiers() { return spendingTiers; }
    public void setSpendingTiers(String spendingTiers) { this.spendingTiers = spendingTiers; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
