package com.wealthview.app.config;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.wealthview.app.config.DemoDataSeeder.ExpenseSpec;
import com.wealthview.app.config.DemoDataSeeder.IncomeSpec;
import com.wealthview.app.config.DemoDataSeeder.TxnSpec;
import com.wealthview.core.holding.HoldingsComputationService;
import com.wealthview.persistence.entity.PropertyEntity;
import com.wealthview.persistence.entity.PropertyExpenseEntity;
import com.wealthview.persistence.entity.PropertyIncomeEntity;
import com.wealthview.persistence.entity.TenantEntity;
import com.wealthview.persistence.entity.TransactionEntity;
import com.wealthview.persistence.repository.AccountRepository;
import com.wealthview.persistence.repository.PropertyExpenseRepository;
import com.wealthview.persistence.repository.PropertyIncomeRepository;
import com.wealthview.persistence.repository.PropertyRepository;
import com.wealthview.persistence.repository.TransactionRepository;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit test for {@link DemoDataSeeder}. Verifies that account seeding persists the
 * transactions and derives holdings via {@link HoldingsComputationService} (never
 * hand-writing holding rows), and that property seeding persists incomes and expenses.
 */
class DemoDataSeederTest {

    private AccountRepository accountRepository;
    private TransactionRepository transactionRepository;
    private HoldingsComputationService holdingsComputationService;
    private PropertyRepository propertyRepository;
    private PropertyIncomeRepository incomeRepository;
    private PropertyExpenseRepository expenseRepository;
    private DemoDataSeeder seeder;

    @BeforeEach
    void setUp() {
        accountRepository = mock(AccountRepository.class);
        transactionRepository = mock(TransactionRepository.class);
        holdingsComputationService = mock(HoldingsComputationService.class);
        propertyRepository = mock(PropertyRepository.class);
        incomeRepository = mock(PropertyIncomeRepository.class);
        expenseRepository = mock(PropertyExpenseRepository.class);

        when(accountRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(propertyRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        seeder = new DemoDataSeeder(accountRepository, transactionRepository,
                holdingsComputationService, propertyRepository, incomeRepository, expenseRepository);
    }

    @Test
    void seedAccount_persistsEachTransaction() {
        var tenant = new TenantEntity("Demo Family");

        seeder.seedAccount(tenant, "Brokerage", "brokerage", "Fidelity", List.of(
                new TxnSpec(LocalDate.parse("2025-01-10"), "buy", "AAPL",
                        new BigDecimal("20"), new BigDecimal("3400.00")),
                new TxnSpec(LocalDate.parse("2025-03-01"), "sell", "AAPL",
                        new BigDecimal("5"), new BigDecimal("950.00")),
                new TxnSpec(LocalDate.parse("2025-04-10"), "dividend", "AAPL",
                        null, new BigDecimal("25.00"))));

        verify(transactionRepository, times(3)).save(any(TransactionEntity.class));
    }

    @Test
    void seedAccount_derivesHoldingsOncePerDistinctSymbol() {
        var tenant = new TenantEntity("Demo Family");

        seeder.seedAccount(tenant, "Brokerage", "brokerage", "Fidelity", List.of(
                new TxnSpec(LocalDate.parse("2025-01-10"), "buy", "AAPL",
                        new BigDecimal("20"), new BigDecimal("3400.00")),
                new TxnSpec(LocalDate.parse("2025-02-15"), "buy", "MSFT",
                        new BigDecimal("10"), new BigDecimal("4200.00")),
                new TxnSpec(LocalDate.parse("2025-03-01"), "sell", "AAPL",
                        new BigDecimal("5"), new BigDecimal("950.00"))));

        // One recompute per distinct symbol — AAPL appears twice but is computed once.
        verify(holdingsComputationService, times(1))
                .recomputeForAccountAndSymbol(any(), eq(tenant), eq("AAPL"));
        verify(holdingsComputationService, times(1))
                .recomputeForAccountAndSymbol(any(), eq(tenant), eq("MSFT"));
    }

    @Test
    void seedAccount_withCashOnlyTransactions_derivesNoHoldings() {
        var tenant = new TenantEntity("Demo Family");

        seeder.seedAccount(tenant, "Chase Checking", "bank", "Chase", List.of(
                new TxnSpec(LocalDate.parse("2025-01-01"), "deposit", null, null, new BigDecimal("5000.00")),
                new TxnSpec(LocalDate.parse("2025-01-15"), "withdrawal", null, null, new BigDecimal("2000.00"))));

        verify(holdingsComputationService, never())
                .recomputeForAccountAndSymbol(any(), any(), any());
    }

    @Test
    void seedProperty_persistsIncomesAndExpenses() {
        var tenant = new TenantEntity("Demo Family");
        var property = mock(PropertyEntity.class);

        seeder.seedProperty(tenant, property,
                List.of(new IncomeSpec(LocalDate.of(2025, 1, 1), new BigDecimal("2200"),
                        "rent", "Monthly rent")),
                List.of(
                        new ExpenseSpec(LocalDate.of(2025, 1, 1), new BigDecimal("1400"),
                                "mortgage", "Monthly mortgage"),
                        new ExpenseSpec(LocalDate.of(2025, 1, 1), new BigDecimal("200"),
                                "insurance", "Insurance")));

        verify(propertyRepository, times(1)).save(property);
        verify(incomeRepository, times(1)).save(any(PropertyIncomeEntity.class));
        verify(expenseRepository, times(2)).save(any(PropertyExpenseEntity.class));
    }
}
