package com.wealthview.app.config;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;

import org.springframework.stereotype.Component;

import com.wealthview.core.holding.HoldingsComputationService;
import com.wealthview.persistence.entity.AccountEntity;
import com.wealthview.persistence.entity.PropertyEntity;
import com.wealthview.persistence.entity.PropertyExpenseEntity;
import com.wealthview.persistence.entity.PropertyIncomeEntity;
import com.wealthview.persistence.entity.TenantEntity;
import com.wealthview.persistence.entity.TransactionEntity;
import com.wealthview.persistence.entity.TransactionType;
import com.wealthview.persistence.repository.AccountRepository;
import com.wealthview.persistence.repository.PropertyExpenseRepository;
import com.wealthview.persistence.repository.PropertyIncomeRepository;
import com.wealthview.persistence.repository.PropertyRepository;
import com.wealthview.persistence.repository.TransactionRepository;

/**
 * Shared demo-data seeding primitives used by both {@link SampleDataInitializer}
 * (dev + docker) and {@link DevDataInitializer} (dev only).
 *
 * <p>Neither initializer owns the mechanics of persisting an investment account with its
 * transactions or a rental property with its cash flows anymore — they build fixtures
 * ({@link TxnSpec}, {@link IncomeSpec}, {@link ExpenseSpec}) and hand them here.
 *
 * <p>Holdings are always <em>derived</em> from the seeded transactions via
 * {@link HoldingsComputationService}, never hand-written. This removes the silent-drift trap
 * where a hand-authored holding row had to be kept in sync with the transactions above it.
 * Callers own <em>what</em> (fixtures) and <em>when</em> (empty-DB guards); this owns <em>how</em>.
 */
@Component
public class DemoDataSeeder {

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final HoldingsComputationService holdingsComputationService;
    private final PropertyRepository propertyRepository;
    private final PropertyIncomeRepository incomeRepository;
    private final PropertyExpenseRepository expenseRepository;

    public DemoDataSeeder(AccountRepository accountRepository,
                          TransactionRepository transactionRepository,
                          HoldingsComputationService holdingsComputationService,
                          PropertyRepository propertyRepository,
                          PropertyIncomeRepository incomeRepository,
                          PropertyExpenseRepository expenseRepository) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.holdingsComputationService = holdingsComputationService;
        this.propertyRepository = propertyRepository;
        this.incomeRepository = incomeRepository;
        this.expenseRepository = expenseRepository;
    }

    /**
     * Persist an account and its transactions, then derive holdings for every distinct
     * priced symbol the transactions touch. Cash-only entries (null/blank symbol) are
     * persisted but produce no holding.
     *
     * @return the saved account
     */
    public AccountEntity seedAccount(TenantEntity tenant, String name, String type,
                                     String institution, List<TxnSpec> transactions) {
        var account = accountRepository.save(new AccountEntity(tenant, name, type, institution));

        for (var txn : transactions) {
            transactionRepository.save(new TransactionEntity(account, tenant, txn.date(),
                    txn.type(), txn.symbol(), txn.quantity(), txn.amount()));
        }

        var symbols = new LinkedHashSet<String>();
        for (var txn : transactions) {
            if (txn.symbol() != null && !txn.symbol().isBlank()) {
                symbols.add(txn.symbol());
            }
        }
        for (var symbol : symbols) {
            holdingsComputationService.recomputeForAccountAndSymbol(account, tenant, symbol);
        }

        return account;
    }

    /**
     * Persist a (possibly pre-configured, e.g. with loan terms) property together with its
     * income and expense records.
     *
     * @return the saved property
     */
    public PropertyEntity seedProperty(TenantEntity tenant, PropertyEntity property,
                                       List<IncomeSpec> incomes, List<ExpenseSpec> expenses) {
        var saved = propertyRepository.save(property);

        for (var income : incomes) {
            incomeRepository.save(new PropertyIncomeEntity(saved, tenant, income.date(),
                    income.amount(), income.category(), income.description()));
        }
        for (var expense : expenses) {
            expenseRepository.save(new PropertyExpenseEntity(saved, tenant, expense.date(),
                    expense.amount(), expense.category(), expense.description()));
        }

        return saved;
    }

    /** A transaction fixture. {@code symbol} and {@code quantity} are null for cash entries. */
    public record TxnSpec(LocalDate date, TransactionType type, String symbol,
                          BigDecimal quantity, BigDecimal amount) {
    }

    /** A property income fixture. */
    public record IncomeSpec(LocalDate date, BigDecimal amount, String category, String description) {
    }

    /** A property expense fixture. */
    public record ExpenseSpec(LocalDate date, BigDecimal amount, String category, String description) {
    }
}
