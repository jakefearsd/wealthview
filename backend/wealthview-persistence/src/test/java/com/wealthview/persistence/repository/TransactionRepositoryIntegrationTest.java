package com.wealthview.persistence.repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;

import com.wealthview.persistence.AbstractIntegrationTest;
import com.wealthview.persistence.entity.AccountEntity;
import com.wealthview.persistence.entity.TenantEntity;
import com.wealthview.persistence.entity.TransactionEntity;

import static org.assertj.core.api.Assertions.assertThat;

class TransactionRepositoryIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private TenantRepository tenantRepository;

    private TenantEntity tenantA;
    private TenantEntity tenantB;
    private AccountEntity accountA;
    private AccountEntity accountB;

    @BeforeEach
    void setUp() {
        tenantA = tenantRepository.save(new TenantEntity("Tenant A"));
        tenantB = tenantRepository.save(new TenantEntity("Tenant B"));
        accountA = accountRepository.save(new AccountEntity(tenantA, "Portfolio A", "brokerage", "Fidelity"));
        accountB = accountRepository.save(new AccountEntity(tenantB, "Portfolio B", "brokerage", "Schwab"));
    }

    // -------------------------------------------------------------------------
    // computeBalance — JPQL CASE/COALESCE query
    // -------------------------------------------------------------------------

    @Test
    void computeBalance_depositsAndWithdrawals_returnsNetBalance() {
        transactionRepository.save(tx(accountA, tenantA, "deposit", null, new BigDecimal("5000.00")));
        transactionRepository.save(tx(accountA, tenantA, "deposit", null, new BigDecimal("3000.00")));
        transactionRepository.save(tx(accountA, tenantA, "withdrawal", null, new BigDecimal("1000.00")));

        var balance = transactionRepository.computeBalance(accountA.getId(), tenantA.getId());

        assertThat(balance).isEqualByComparingTo("7000.00");
    }

    @Test
    void computeBalance_noTransactions_returnsZero() {
        var balance = transactionRepository.computeBalance(accountA.getId(), tenantA.getId());

        assertThat(balance).isEqualByComparingTo("0");
    }

    @Test
    void computeBalance_isolatesTenants() {
        transactionRepository.save(tx(accountA, tenantA, "deposit", null, new BigDecimal("10000.00")));
        transactionRepository.save(tx(accountB, tenantB, "deposit", null, new BigDecimal("99999.00")));

        var balanceA = transactionRepository.computeBalance(accountA.getId(), tenantA.getId());

        // Tenant A's balance must not include Tenant B's transaction
        assertThat(balanceA).isEqualByComparingTo("10000.00");
    }

    // -------------------------------------------------------------------------
    // computeBalancesByAccountIds — batch balance query
    // -------------------------------------------------------------------------

    @Test
    void computeBalancesByAccountIds_returnsBalancesForMultipleAccounts() {
        var accountA2 = accountRepository.save(new AccountEntity(tenantA, "Portfolio A2", "brokerage", "Vanguard"));
        transactionRepository.save(tx(accountA, tenantA, "deposit", null, new BigDecimal("5000.00")));
        transactionRepository.save(tx(accountA2, tenantA, "deposit", null, new BigDecimal("3000.00")));

        var results = transactionRepository.computeBalancesByAccountIds(
                tenantA.getId(), List.of(accountA.getId(), accountA2.getId()));

        assertThat(results).hasSize(2);
    }

    @Test
    void computeBalancesByAccountIds_emptyAccountList_returnsEmptyList() {
        var results = transactionRepository.computeBalancesByAccountIds(tenantA.getId(), List.of());

        assertThat(results).isEmpty();
    }

    // -------------------------------------------------------------------------
    // findExistingImportHashes — set-intersection query
    // -------------------------------------------------------------------------

    @Test
    void findExistingImportHashes_returnsOnlyMatchingHashes() {
        var t = tx(accountA, tenantA, "buy", "AAPL", new BigDecimal("1500.00"));
        t.setImportHash("hash-abc");
        transactionRepository.save(t);

        var t2 = tx(accountA, tenantA, "buy", "MSFT", new BigDecimal("800.00"));
        t2.setImportHash("hash-xyz");
        transactionRepository.save(t2);

        var found = transactionRepository.findExistingImportHashes(
                tenantA.getId(), accountA.getId(), Set.of("hash-abc", "hash-new-1", "hash-new-2"));

        assertThat(found).containsExactly("hash-abc");
        assertThat(found).doesNotContain("hash-xyz", "hash-new-1", "hash-new-2");
    }

    @Test
    void findExistingImportHashes_noMatches_returnsEmptySet() {
        var found = transactionRepository.findExistingImportHashes(
                tenantA.getId(), accountA.getId(), Set.of("nonexistent-hash"));

        assertThat(found).isEmpty();
    }

    @Test
    void findExistingImportHashes_isolatesTenants() {
        var t = tx(accountB, tenantB, "buy", "AAPL", new BigDecimal("1500.00"));
        t.setImportHash("hash-b");
        transactionRepository.save(t);

        // Tenant A should NOT see Tenant B's import hashes
        var found = transactionRepository.findExistingImportHashes(
                tenantA.getId(), accountA.getId(), Set.of("hash-b"));

        assertThat(found).isEmpty();
    }

    // -------------------------------------------------------------------------
    // existsByTenant_IdAndAccount_IdAndImportHash
    // -------------------------------------------------------------------------

    @Test
    void existsByTenantIdAndAccountIdAndImportHash_exists_returnsTrue() {
        var t = tx(accountA, tenantA, "buy", "AAPL", new BigDecimal("1500.00"));
        t.setImportHash("check-hash");
        transactionRepository.save(t);

        assertThat(transactionRepository.existsByTenant_IdAndAccount_IdAndImportHash(
                tenantA.getId(), accountA.getId(), "check-hash")).isTrue();
    }

    @Test
    void existsByTenantIdAndAccountIdAndImportHash_notExists_returnsFalse() {
        assertThat(transactionRepository.existsByTenant_IdAndAccount_IdAndImportHash(
                tenantA.getId(), accountA.getId(), "nope")).isFalse();
    }

    @Test
    void existsByTenantIdAndAccountIdAndImportHash_wrongTenant_returnsFalse() {
        var t = tx(accountA, tenantA, "buy", "AAPL", new BigDecimal("1500.00"));
        t.setImportHash("shared-hash");
        transactionRepository.save(t);

        // Tenant B must NOT match Tenant A's import hash
        assertThat(transactionRepository.existsByTenant_IdAndAccount_IdAndImportHash(
                tenantB.getId(), accountA.getId(), "shared-hash")).isFalse();
    }

    // -------------------------------------------------------------------------
    // findDistinctSymbolsAcrossAllTenants — global @Query (no tenant filter)
    // -------------------------------------------------------------------------

    @Test
    void findDistinctSymbolsAcrossAllTenants_returnsDeduplicatedSymbols() {
        transactionRepository.save(tx(accountA, tenantA, "buy", "AAPL", new BigDecimal("1500.00")));
        transactionRepository.save(tx(accountA, tenantA, "buy", "AAPL", new BigDecimal("1500.00"))); // duplicate
        transactionRepository.save(tx(accountB, tenantB, "buy", "MSFT", new BigDecimal("800.00")));
        transactionRepository.save(tx(accountA, tenantA, "deposit", null, new BigDecimal("100.00"))); // null symbol

        var symbols = transactionRepository.findDistinctSymbolsAcrossAllTenants();

        assertThat(symbols).containsExactlyInAnyOrder("AAPL", "MSFT");
        assertThat(symbols).doesNotContainNull();
    }

    @Test
    void findDistinctSymbolsAcrossAllTenants_noSymbols_returnsEmpty() {
        transactionRepository.save(tx(accountA, tenantA, "deposit", null, new BigDecimal("1000.00")));

        var symbols = transactionRepository.findDistinctSymbolsAcrossAllTenants();

        assertThat(symbols).isEmpty();
    }

    // -------------------------------------------------------------------------
    // findBySymbolAndDateOnOrBefore
    // -------------------------------------------------------------------------

    @Test
    void findBySymbolAndDateOnOrBefore_returnsTransactionsUpToDate() {
        transactionRepository.save(txOnDate(accountA, tenantA, "buy", "AAPL",
                LocalDate.of(2024, 1, 1), new BigDecimal("1000.00")));
        transactionRepository.save(txOnDate(accountA, tenantA, "buy", "AAPL",
                LocalDate.of(2024, 6, 15), new BigDecimal("2000.00")));
        transactionRepository.save(txOnDate(accountB, tenantB, "buy", "AAPL",
                LocalDate.of(2025, 1, 1), new BigDecimal("3000.00"))); // after cutoff

        var result = transactionRepository.findBySymbolAndDateOnOrBefore("AAPL", LocalDate.of(2024, 12, 31));

        assertThat(result).hasSize(2);
        assertThat(result).extracting(TransactionEntity::getAmount)
                .allSatisfy(amt -> assertThat(amt).isLessThanOrEqualTo(new BigDecimal("2000.00")));
    }

    @Test
    void findBySymbolAndDateOnOrBefore_noResults_returnsEmpty() {
        var result = transactionRepository.findBySymbolAndDateOnOrBefore("GOOG", LocalDate.of(2024, 1, 1));

        assertThat(result).isEmpty();
    }

    // -------------------------------------------------------------------------
    // findEarliestDateBySymbol
    // -------------------------------------------------------------------------

    @Test
    void findEarliestDateBySymbol_multipleTransactions_returnsMinDate() {
        transactionRepository.save(txOnDate(accountA, tenantA, "buy", "VOO",
                LocalDate.of(2020, 3, 15), new BigDecimal("300.00")));
        transactionRepository.save(txOnDate(accountA, tenantA, "buy", "VOO",
                LocalDate.of(2019, 7, 1), new BigDecimal("300.00")));
        transactionRepository.save(txOnDate(accountA, tenantA, "buy", "VOO",
                LocalDate.of(2022, 1, 10), new BigDecimal("300.00")));

        var earliest = transactionRepository.findEarliestDateBySymbol("VOO");

        assertThat(earliest).isPresent();
        assertThat(earliest.get()).isEqualTo(LocalDate.of(2019, 7, 1));
    }

    @Test
    void findEarliestDateBySymbol_noTransactions_returnsEmpty() {
        var earliest = transactionRepository.findEarliestDateBySymbol("NONEXISTENT");

        assertThat(earliest).isEmpty();
    }

    // -------------------------------------------------------------------------
    // findDistinctTenantIdsBySymbol
    // -------------------------------------------------------------------------

    @Test
    void findDistinctTenantIdsBySymbol_returnsAllTenantsHoldingSymbol() {
        transactionRepository.save(tx(accountA, tenantA, "buy", "AAPL", new BigDecimal("1000.00")));
        transactionRepository.save(tx(accountB, tenantB, "buy", "AAPL", new BigDecimal("2000.00")));

        var tenantIds = transactionRepository.findDistinctTenantIdsBySymbol("AAPL");

        assertThat(tenantIds).containsExactlyInAnyOrder(tenantA.getId(), tenantB.getId());
    }

    @Test
    void findDistinctTenantIdsBySymbol_symbolNotHeld_returnsEmpty() {
        var tenantIds = transactionRepository.findDistinctTenantIdsBySymbol("NOTREAL");

        assertThat(tenantIds).isEmpty();
    }

    // -------------------------------------------------------------------------
    // findByAccount_IdAndTenant_Id (paginated) — multi-tenant isolation
    // -------------------------------------------------------------------------

    @Test
    void findByAccountIdAndTenantId_paginated_isolatesTenants() {
        transactionRepository.save(tx(accountA, tenantA, "buy", "AAPL", new BigDecimal("1000.00")));
        transactionRepository.save(tx(accountB, tenantB, "buy", "MSFT", new BigDecimal("500.00")));

        var pageA = transactionRepository.findByAccount_IdAndTenant_Id(
                accountA.getId(), tenantA.getId(), PageRequest.of(0, 10));

        assertThat(pageA.getTotalElements()).isEqualTo(1);
        assertThat(pageA.getContent().get(0).getSymbol()).isEqualTo("AAPL");
    }

    @Test
    void findByAccountIdAndTenantId_wrongTenant_returnsEmpty() {
        transactionRepository.save(tx(accountA, tenantA, "buy", "AAPL", new BigDecimal("1000.00")));

        var page = transactionRepository.findByAccount_IdAndTenant_Id(
                accountA.getId(), tenantB.getId(), PageRequest.of(0, 10));

        assertThat(page.getTotalElements()).isZero();
    }

    // -------------------------------------------------------------------------
    // findByTenant_IdAndSymbol — tenant + symbol scoped finder
    // -------------------------------------------------------------------------

    @Test
    void findByTenantIdAndSymbol_returnsOnlyMatchingSymbolForTenant() {
        transactionRepository.save(tx(accountA, tenantA, "buy", "AAPL", new BigDecimal("1000.00")));
        transactionRepository.save(tx(accountA, tenantA, "buy", "AAPL", new BigDecimal("2000.00")));
        transactionRepository.save(tx(accountA, tenantA, "buy", "MSFT", new BigDecimal("500.00")));

        var result = transactionRepository.findByTenant_IdAndSymbol(tenantA.getId(), "AAPL");

        assertThat(result).hasSize(2);
        assertThat(result).extracting(TransactionEntity::getSymbol).containsOnly("AAPL");
    }

    @Test
    void findByTenantIdAndSymbol_isolatesTenants() {
        transactionRepository.save(tx(accountA, tenantA, "buy", "AAPL", new BigDecimal("1000.00")));
        transactionRepository.save(tx(accountB, tenantB, "buy", "AAPL", new BigDecimal("9999.00")));

        var result = transactionRepository.findByTenant_IdAndSymbol(tenantA.getId(), "AAPL");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getAmount()).isEqualByComparingTo("1000.00");
    }

    @Test
    void findByTenantIdAndSymbol_symbolNotHeld_returnsEmpty() {
        transactionRepository.save(tx(accountA, tenantA, "buy", "AAPL", new BigDecimal("1000.00")));

        var result = transactionRepository.findByTenant_IdAndSymbol(tenantA.getId(), "GOOG");

        assertThat(result).isEmpty();
    }

    // -------------------------------------------------------------------------
    // findByIdAndTenant_Id
    // -------------------------------------------------------------------------

    @Test
    void findByIdAndTenantId_correctTenant_returnsTransaction() {
        var saved = transactionRepository.save(tx(accountA, tenantA, "buy", "AAPL", new BigDecimal("1000.00")));

        var found = transactionRepository.findByIdAndTenant_Id(saved.getId(), tenantA.getId());

        assertThat(found).isPresent();
    }

    @Test
    void findByIdAndTenantId_wrongTenant_returnsEmpty() {
        var saved = transactionRepository.save(tx(accountA, tenantA, "buy", "AAPL", new BigDecimal("1000.00")));

        var found = transactionRepository.findByIdAndTenant_Id(saved.getId(), tenantB.getId());

        assertThat(found).isEmpty();
    }

    @Test
    void findByIdAndTenantId_nonExistentId_returnsEmpty() {
        var found = transactionRepository.findByIdAndTenant_Id(UUID.randomUUID(), tenantA.getId());

        assertThat(found).isEmpty();
    }

    // -------------------------------------------------------------------------
    // deleteByAccount_IdAndTenant_Id — scoped delete
    // -------------------------------------------------------------------------

    @Test
    void deleteByAccountIdAndTenantId_deletesOnlyTargetTenantTransactions() {
        transactionRepository.save(tx(accountA, tenantA, "buy", "AAPL", new BigDecimal("1000.00")));
        transactionRepository.save(tx(accountB, tenantB, "buy", "MSFT", new BigDecimal("500.00")));

        transactionRepository.deleteByAccount_IdAndTenant_Id(accountA.getId(), tenantA.getId());

        assertThat(transactionRepository.findByTenant_Id(tenantA.getId())).isEmpty();
        assertThat(transactionRepository.findByTenant_Id(tenantB.getId())).hasSize(1);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static TransactionEntity tx(AccountEntity account, TenantEntity tenant,
                                         String type, String symbol, BigDecimal amount) {
        return txOnDate(account, tenant, type, symbol, LocalDate.of(2024, 6, 1), amount);
    }

    private static TransactionEntity txOnDate(AccountEntity account, TenantEntity tenant,
                                               String type, String symbol, LocalDate date, BigDecimal amount) {
        return new TransactionEntity(account, tenant, date, type, symbol,
                symbol != null ? new BigDecimal("1.0000") : null, amount);
    }
}
