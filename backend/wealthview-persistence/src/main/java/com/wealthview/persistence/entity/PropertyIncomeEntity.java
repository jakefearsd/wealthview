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
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "property_income")
public class PropertyIncomeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "property_id", nullable = false)
    private PropertyEntity property;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id", nullable = false)
    private TenantEntity tenant;

    @Column(nullable = false)
    private LocalDate date;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(nullable = false)
    private String category;

    private String description;

    @Column(nullable = false)
    private String frequency = "monthly";

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt = OffsetDateTime.now();

    protected PropertyIncomeEntity() {
    }

    public PropertyIncomeEntity(PropertyEntity property, TenantEntity tenant,
                                LocalDate date, BigDecimal amount, String category, String description) {
        this(property, tenant, date, amount, category, description, "monthly");
    }

    public PropertyIncomeEntity(PropertyEntity property, TenantEntity tenant,
                                LocalDate date, BigDecimal amount, String category,
                                String description, String frequency) {
        this.property = property;
        this.tenant = tenant;
        this.date = date;
        this.amount = amount;
        this.category = category;
        this.description = description;
        this.frequency = frequency;
    }

    public UUID getId() { return id; }
    public PropertyEntity getProperty() { return property; }
    public LocalDate getDate() { return date; }
    public BigDecimal getAmount() { return amount; }
    public String getCategory() { return category; }
    public String getDescription() { return description; }
    public String getFrequency() { return frequency; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
