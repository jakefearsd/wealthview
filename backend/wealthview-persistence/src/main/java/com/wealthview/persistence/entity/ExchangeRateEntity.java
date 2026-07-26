package com.wealthview.persistence.entity;

import java.math.BigDecimal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.Filter;

@Entity
@Table(name = "exchange_rates")
@Filter(name = "tenantFilter", condition = "tenant_id = :tenantId")
public class ExchangeRateEntity extends UuidAuditable {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id", nullable = false)
    private TenantEntity tenant;

    @Column(name = "currency_code", nullable = false)
    private String currencyCode;

    @Column(name = "rate_to_usd", nullable = false, precision = 19, scale = 8)
    private BigDecimal rateToUsd;

    protected ExchangeRateEntity() {
    }

    public ExchangeRateEntity(TenantEntity tenant, String currencyCode, BigDecimal rateToUsd) {
        this.tenant = tenant;
        this.currencyCode = currencyCode;
        this.rateToUsd = rateToUsd;
    }

    public TenantEntity getTenant() {
        return tenant;
    }

    public String getCurrencyCode() {
        return currencyCode;
    }

    public void setCurrencyCode(String currencyCode) {
        this.currencyCode = currencyCode;
    }

    public BigDecimal getRateToUsd() {
        return rateToUsd;
    }

    public void setRateToUsd(BigDecimal rateToUsd) {
        this.rateToUsd = rateToUsd;
    }

}
