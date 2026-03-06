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
@Table(name = "property_valuations")
public class PropertyValuationEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "property_id", nullable = false)
    private PropertyEntity property;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id", nullable = false)
    private TenantEntity tenant;

    @Column(name = "valuation_date", nullable = false)
    private LocalDate valuationDate;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal value;

    @Column(nullable = false)
    private String source;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt = OffsetDateTime.now();

    protected PropertyValuationEntity() {
    }

    public PropertyValuationEntity(PropertyEntity property, TenantEntity tenant,
                                    LocalDate valuationDate, BigDecimal value, String source) {
        this.property = property;
        this.tenant = tenant;
        this.valuationDate = valuationDate;
        this.value = value;
        this.source = source;
    }

    public UUID getId() { return id; }
    public PropertyEntity getProperty() { return property; }
    public TenantEntity getTenant() { return tenant; }
    public LocalDate getValuationDate() { return valuationDate; }
    public BigDecimal getValue() { return value; }
    public void setValue(BigDecimal value) { this.value = value; }
    public String getSource() { return source; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
