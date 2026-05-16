package com.wealthview.persistence.repository;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.wealthview.persistence.AbstractIntegrationTest;
import com.wealthview.persistence.entity.AccountEntity;
import com.wealthview.persistence.entity.HoldingEntity;
import com.wealthview.persistence.entity.TenantEntity;

import static org.assertj.core.api.Assertions.assertThat;

class HoldingRepositoryIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private HoldingRepository holdingRepository;

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
        accountA = accountRepository.save(new AccountEntity(tenantA, "Account A", "brokerage", "Fidelity"));
        accountB = accountRepository.save(new AccountEntity(tenantB, "Account B", "brokerage", "Schwab"));
    }

    // -------------------------------------------------------------------------
    // findDistinctSymbols — custom @Query: only returns symbols with quantity > 0
    // -------------------------------------------------------------------------

    @Test
    void findDistinctSymbols_returnsOnlySymbolsWithPositiveQuantity() {
        holdingRepository.save(new HoldingEntity(accountA, tenantA, "AAPL",
                new BigDecimal("10.00"), new BigDecimal("1500.00")));
        holdingRepository.save(new HoldingEntity(accountA, tenantA, "MSFT",
                BigDecimal.ZERO, new BigDecimal("500.00")));  // zero quantity — excluded
        holdingRepository.save(new HoldingEntity(accountB, tenantB, "VOO",
                new BigDecimal("5.00"), new BigDecimal("2000.00")));

        var symbols = holdingRepository.findDistinctSymbols();

        assertThat(symbols).containsExactlyInAnyOrder("AAPL", "VOO");
        assertThat(symbols).doesNotContain("MSFT");
    }

    @Test
    void findDistinctSymbols_noHoldings_returnsEmptyList() {
        var symbols = holdingRepository.findDistinctSymbols();

        assertThat(symbols).isEmpty();
    }

    @Test
    void findDistinctSymbols_deduplicatesSymbolAcrossTenants() {
        holdingRepository.save(new HoldingEntity(accountA, tenantA, "AAPL",
                new BigDecimal("3.00"), new BigDecimal("450.00")));
        holdingRepository.save(new HoldingEntity(accountB, tenantB, "AAPL",
                new BigDecimal("7.00"), new BigDecimal("1050.00")));

        var symbols = holdingRepository.findDistinctSymbols();

        assertThat(symbols).containsExactly("AAPL");  // deduplicated — AAPL only once
    }

    // -------------------------------------------------------------------------
    // findByAccount_IdAndSymbol
    // -------------------------------------------------------------------------

    @Test
    void findByAccountIdAndSymbol_exists_returnsHolding() {
        holdingRepository.save(new HoldingEntity(accountA, tenantA, "AAPL",
                new BigDecimal("10.00"), new BigDecimal("1500.00")));

        var found = holdingRepository.findByAccount_IdAndSymbol(accountA.getId(), "AAPL");

        assertThat(found).isPresent();
        assertThat(found.get().getQuantity()).isEqualByComparingTo("10.00");
    }

    @Test
    void findByAccountIdAndSymbol_notFound_returnsEmpty() {
        var found = holdingRepository.findByAccount_IdAndSymbol(accountA.getId(), "GOOG");

        assertThat(found).isEmpty();
    }

    // -------------------------------------------------------------------------
    // Multi-tenant isolation — findByAccount_IdAndTenant_Id
    // -------------------------------------------------------------------------

    @Test
    void findByAccountIdAndTenantId_isolatesTenants() {
        holdingRepository.save(new HoldingEntity(accountA, tenantA, "AAPL",
                new BigDecimal("10.00"), new BigDecimal("1500.00")));
        holdingRepository.save(new HoldingEntity(accountB, tenantB, "MSFT",
                new BigDecimal("5.00"), new BigDecimal("800.00")));

        var holdingsA = holdingRepository.findByAccount_IdAndTenant_Id(accountA.getId(), tenantA.getId());
        var holdingsB = holdingRepository.findByAccount_IdAndTenant_Id(accountB.getId(), tenantB.getId());

        assertThat(holdingsA).hasSize(1);
        assertThat(holdingsA.get(0).getSymbol()).isEqualTo("AAPL");
        assertThat(holdingsB).hasSize(1);
        assertThat(holdingsB.get(0).getSymbol()).isEqualTo("MSFT");
    }

    @Test
    void findByAccountIdAndTenantId_wrongTenant_returnsEmpty() {
        holdingRepository.save(new HoldingEntity(accountA, tenantA, "AAPL",
                new BigDecimal("10.00"), new BigDecimal("1500.00")));

        // Tenant B tries to access Tenant A's account holdings
        var result = holdingRepository.findByAccount_IdAndTenant_Id(accountA.getId(), tenantB.getId());

        assertThat(result).isEmpty();
    }

    // -------------------------------------------------------------------------
    // findByTenant_Id (all holdings for a tenant)
    // -------------------------------------------------------------------------

    @Test
    void findByTenantId_returnsAllHoldingsForTenant() {
        holdingRepository.save(new HoldingEntity(accountA, tenantA, "AAPL",
                new BigDecimal("10.00"), new BigDecimal("1500.00")));
        holdingRepository.save(new HoldingEntity(accountA, tenantA, "MSFT",
                new BigDecimal("5.00"), new BigDecimal("800.00")));
        holdingRepository.save(new HoldingEntity(accountB, tenantB, "VOO",
                new BigDecimal("3.00"), new BigDecimal("900.00")));

        var holdingsA = holdingRepository.findByTenant_Id(tenantA.getId());

        assertThat(holdingsA).hasSize(2);
        assertThat(holdingsA).extracting(HoldingEntity::getSymbol)
                .containsExactlyInAnyOrder("AAPL", "MSFT");
    }

    // -------------------------------------------------------------------------
    // findByIdAndTenant_Id — ownership check
    // -------------------------------------------------------------------------

    @Test
    void findByIdAndTenantId_correctTenant_returnsHolding() {
        var saved = holdingRepository.save(new HoldingEntity(accountA, tenantA, "AAPL",
                new BigDecimal("10.00"), new BigDecimal("1500.00")));

        var found = holdingRepository.findByIdAndTenant_Id(saved.getId(), tenantA.getId());

        assertThat(found).isPresent();
    }

    @Test
    void findByIdAndTenantId_wrongTenant_returnsEmpty() {
        var saved = holdingRepository.save(new HoldingEntity(accountA, tenantA, "AAPL",
                new BigDecimal("10.00"), new BigDecimal("1500.00")));

        var found = holdingRepository.findByIdAndTenant_Id(saved.getId(), tenantB.getId());

        assertThat(found).isEmpty();
    }

    // -------------------------------------------------------------------------
    // deleteByAccount_IdAndTenant_Id — scoped delete
    // -------------------------------------------------------------------------

    @Test
    void deleteByAccountIdAndTenantId_deletesOnlyTargetAccountHoldings() {
        holdingRepository.save(new HoldingEntity(accountA, tenantA, "AAPL",
                new BigDecimal("10.00"), new BigDecimal("1500.00")));
        holdingRepository.save(new HoldingEntity(accountB, tenantB, "MSFT",
                new BigDecimal("5.00"), new BigDecimal("800.00")));

        holdingRepository.deleteByAccount_IdAndTenant_Id(accountA.getId(), tenantA.getId());

        assertThat(holdingRepository.findByTenant_Id(tenantA.getId())).isEmpty();
        assertThat(holdingRepository.findByTenant_Id(tenantB.getId())).hasSize(1);
    }

    @Test
    void deleteByAccountIdAndTenantId_wrongTenant_deletesNothing() {
        holdingRepository.save(new HoldingEntity(accountA, tenantA, "AAPL",
                new BigDecimal("10.00"), new BigDecimal("1500.00")));

        // Attempting to delete Tenant A's holdings scoped to Tenant B should be a no-op
        holdingRepository.deleteByAccount_IdAndTenant_Id(accountA.getId(), tenantB.getId());

        assertThat(holdingRepository.findByTenant_Id(tenantA.getId())).hasSize(1);
    }

    @Test
    void findByAccountIdAndTenantId_emptyAccount_returnsEmpty() {
        var result = holdingRepository.findByAccount_IdAndTenant_Id(UUID.randomUUID(), tenantA.getId());

        assertThat(result).isEmpty();
    }
}
