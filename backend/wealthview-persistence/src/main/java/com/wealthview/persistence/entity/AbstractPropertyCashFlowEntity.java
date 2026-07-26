package com.wealthview.persistence.entity;

import java.math.BigDecimal;
import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MappedSuperclass;
import org.hibernate.annotations.Filter;

@MappedSuperclass
@Filter(name = "tenantFilter", condition = "tenant_id = :tenantId")
public abstract class AbstractPropertyCashFlowEntity extends UuidAuditable {

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

    protected AbstractPropertyCashFlowEntity() {
    }

    protected AbstractPropertyCashFlowEntity(PropertyEntity property, TenantEntity tenant,
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

    public PropertyEntity getProperty() {
        return property;
    }

    public LocalDate getDate() {
        return date;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCategory() {
        return category;
    }

    public String getDescription() {
        return description;
    }

    public String getFrequency() {
        return frequency;
    }

}
