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
@Table(name = "exchange_rates")
public class ExchangeRateEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id", nullable = false)
    private TenantEntity tenant;

    @Column(name = "currency_code", nullable = false)
    private String currencyCode;

    @Column(name = "rate_to_usd", nullable = false, precision = 19, scale = 8)
    private BigDecimal rateToUsd;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt = OffsetDateTime.now();

    protected ExchangeRateEntity() {
    }

    public ExchangeRateEntity(TenantEntity tenant, String currencyCode, BigDecimal rateToUsd) {
        this.tenant = tenant;
        this.currencyCode = currencyCode;
        this.rateToUsd = rateToUsd;
    }

    public UUID getId() { return id; }
    public TenantEntity getTenant() { return tenant; }
    public String getCurrencyCode() { return currencyCode; }
    public void setCurrencyCode(String currencyCode) { this.currencyCode = currencyCode; }
    public BigDecimal getRateToUsd() { return rateToUsd; }
    public void setRateToUsd(BigDecimal rateToUsd) { this.rateToUsd = rateToUsd; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
