package com.wealthview.persistence.entity;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.Filter;

@Entity
@Table(name = "accounts")
@Filter(name = "tenantFilter", condition = "tenant_id = :tenantId")
public class AccountEntity extends UuidAuditable {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id", nullable = false)
    private TenantEntity tenant;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String type;

    private String institution;

    @Column(nullable = false)
    private String currency = "USD";

    protected AccountEntity() {
    }

    public AccountEntity(TenantEntity tenant, String name, String type, String institution) {
        this(tenant, name, type, institution, "USD");
    }

    public AccountEntity(TenantEntity tenant, String name, String type, String institution, String currency) {
        this.tenant = tenant;
        this.name = name;
        this.type = type;
        this.institution = institution;
        this.currency = currency != null ? currency : "USD";
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

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    /**
     * Whether this account's type is the "bank" account type -- a cash-balance account
     * (balance computed from transactions) rather than a holdings-based investment account.
     * Not to be confused with a transaction's type.
     */
    public boolean isBank() {
        return "bank".equals(type);
    }

    public String getInstitution() {
        return institution;
    }

    public void setInstitution(String institution) {
        this.institution = institution;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

}
