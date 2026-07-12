package com.wealthview.persistence.entity;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

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

@Entity
@Table(name = "projection_accounts")
public class ProjectionAccountEntity extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "scenario_id", nullable = false)
    private ProjectionScenarioEntity scenario;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "linked_account_id")
    private AccountEntity linkedAccount;

    @Column(name = "initial_balance", precision = 19, scale = 4)
    private BigDecimal initialBalance;

    @Column(name = "annual_contribution", nullable = false, precision = 19, scale = 4)
    private BigDecimal annualContribution = BigDecimal.ZERO;

    @Column(name = "expected_return", precision = 5, scale = 4)
    private BigDecimal expectedReturn;

    @Column(name = "cost_basis", precision = 19, scale = 4)
    private BigDecimal costBasis;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "allocation")
    private Map<String, BigDecimal> allocation;

    @Column(name = "account_type", nullable = false)
    private String accountType = "taxable";

    @Column(name = "owner", nullable = false)
    private String owner = "primary";

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

    public UUID getId() {
        return id;
    }

    public ProjectionScenarioEntity getScenario() {
        return scenario;
    }

    public void setScenario(ProjectionScenarioEntity scenario) {
        this.scenario = scenario;
    }

    public AccountEntity getLinkedAccount() {
        return linkedAccount;
    }

    public BigDecimal getInitialBalance() {
        return initialBalance;
    }

    public void setInitialBalance(BigDecimal initialBalance) {
        this.initialBalance = initialBalance;
    }

    public BigDecimal getAnnualContribution() {
        return annualContribution;
    }

    public void setAnnualContribution(BigDecimal annualContribution) {
        this.annualContribution = annualContribution;
    }

    public BigDecimal getExpectedReturn() {
        return expectedReturn;
    }

    public void setExpectedReturn(BigDecimal expectedReturn) {
        this.expectedReturn = expectedReturn;
    }

    public BigDecimal getCostBasis() {
        return costBasis;
    }

    public void setCostBasis(BigDecimal costBasis) {
        this.costBasis = costBasis;
    }

    public Map<String, BigDecimal> getAllocation() {
        return allocation;
    }

    public void setAllocation(Map<String, BigDecimal> allocation) {
        this.allocation = allocation;
    }

    public String getAccountType() {
        return accountType;
    }

    public void setAccountType(String accountType) {
        this.accountType = accountType;
    }

    public String getOwner() {
        return owner;
    }

    public void setOwner(String owner) {
        this.owner = owner;
    }

}
