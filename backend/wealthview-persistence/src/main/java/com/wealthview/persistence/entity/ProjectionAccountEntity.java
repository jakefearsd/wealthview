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
@Table(name = "projection_accounts")
public class ProjectionAccountEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "scenario_id", nullable = false)
    private ProjectionScenarioEntity scenario;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "linked_account_id")
    private AccountEntity linkedAccount;

    @Column(name = "initial_balance", nullable = false, precision = 19, scale = 4)
    private BigDecimal initialBalance = BigDecimal.ZERO;

    @Column(name = "annual_contribution", nullable = false, precision = 19, scale = 4)
    private BigDecimal annualContribution = BigDecimal.ZERO;

    @Column(name = "expected_return", nullable = false, precision = 5, scale = 4)
    private BigDecimal expectedReturn = new BigDecimal("0.0700");

    @Column(name = "account_type", nullable = false)
    private String accountType = "taxable";

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt = OffsetDateTime.now();

    protected ProjectionAccountEntity() {
    }

    public ProjectionAccountEntity(ProjectionScenarioEntity scenario, AccountEntity linkedAccount,
                                    BigDecimal initialBalance, BigDecimal annualContribution,
                                    BigDecimal expectedReturn) {
        this.scenario = scenario;
        this.linkedAccount = linkedAccount;
        this.initialBalance = initialBalance;
        this.annualContribution = annualContribution;
        this.expectedReturn = expectedReturn;
    }

    public ProjectionAccountEntity(ProjectionScenarioEntity scenario, AccountEntity linkedAccount,
                                    BigDecimal initialBalance, BigDecimal annualContribution,
                                    BigDecimal expectedReturn, String accountType) {
        this(scenario, linkedAccount, initialBalance, annualContribution, expectedReturn);
        this.accountType = accountType != null ? accountType : "taxable";
    }

    public UUID getId() { return id; }
    public ProjectionScenarioEntity getScenario() { return scenario; }
    public void setScenario(ProjectionScenarioEntity scenario) { this.scenario = scenario; }
    public AccountEntity getLinkedAccount() { return linkedAccount; }
    public BigDecimal getInitialBalance() { return initialBalance; }
    public void setInitialBalance(BigDecimal initialBalance) { this.initialBalance = initialBalance; }
    public BigDecimal getAnnualContribution() { return annualContribution; }
    public void setAnnualContribution(BigDecimal annualContribution) { this.annualContribution = annualContribution; }
    public BigDecimal getExpectedReturn() { return expectedReturn; }
    public void setExpectedReturn(BigDecimal expectedReturn) { this.expectedReturn = expectedReturn; }
    public String getAccountType() { return accountType; }
    public void setAccountType(String accountType) { this.accountType = accountType; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
