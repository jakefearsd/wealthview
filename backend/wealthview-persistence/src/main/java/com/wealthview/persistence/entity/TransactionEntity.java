package com.wealthview.persistence.entity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.Filter;

@Entity
@Table(name = "transactions")
@Filter(name = "tenantFilter", condition = "tenant_id = :tenantId")
public class TransactionEntity extends UuidAuditable {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "account_id", nullable = false)
    private AccountEntity account;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id", nullable = false)
    private TenantEntity tenant;

    @Column(nullable = false)
    private LocalDate date;

    @Convert(converter = TransactionTypeConverter.class)
    @Column(nullable = false)
    private TransactionType type;

    private String symbol;

    @Column(precision = 19, scale = 4)
    private BigDecimal quantity;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(name = "import_hash")
    private String importHash;

    protected TransactionEntity() {
    }

    public TransactionEntity(AccountEntity account, TenantEntity tenant, LocalDate date,
                             TransactionType type, String symbol, BigDecimal quantity, BigDecimal amount) {
        this.account = account;
        this.tenant = tenant;
        this.date = date;
        this.type = type;
        this.symbol = symbol;
        this.quantity = quantity;
        this.amount = amount;
    }

    public AccountEntity getAccount() {
        return account;
    }

    public UUID getAccountId() {
        return account.getId();
    }

    public TenantEntity getTenant() {
        return tenant;
    }

    public UUID getTenantId() {
        return tenant.getId();
    }

    public LocalDate getDate() {
        return date;
    }

    public void setDate(LocalDate date) {
        this.date = date;
    }

    public TransactionType getType() {
        return type;
    }

    public void setType(TransactionType type) {
        this.type = type;
    }

    public String getSymbol() {
        return symbol;
    }

    public void setSymbol(String symbol) {
        this.symbol = symbol;
    }

    public BigDecimal getQuantity() {
        return quantity;
    }

    public void setQuantity(BigDecimal quantity) {
        this.quantity = quantity;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public String getImportHash() {
        return importHash;
    }

    public void setImportHash(String importHash) {
        this.importHash = importHash;
    }
}
