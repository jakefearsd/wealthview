package com.wealthview.persistence.repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.wealthview.persistence.AbstractIntegrationTest;
import com.wealthview.persistence.entity.StockSplitAdjustmentEntity;
import com.wealthview.persistence.entity.StockSplitEntity;
import com.wealthview.persistence.entity.TenantEntity;

import static org.assertj.core.api.Assertions.assertThat;

class StockSplitRepositoryIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private StockSplitRepository stockSplitRepository;

    @Autowired
    private StockSplitAdjustmentRepository stockSplitAdjustmentRepository;

    @Autowired
    private TenantRepository tenantRepository;

    private TenantEntity tenantA;
    private TenantEntity tenantB;

    @BeforeEach
    void setUp() {
        tenantA = tenantRepository.save(new TenantEntity("Tenant A"));
        tenantB = tenantRepository.save(new TenantEntity("Tenant B"));
    }

    // -------------------------------------------------------------------------
    // findBySymbolAndEffectiveDate
    // -------------------------------------------------------------------------

    @Test
    void findBySymbolAndEffectiveDate_exists_returnsSplit() {
        stockSplitRepository.save(new StockSplitEntity("AAPL", LocalDate.of(2020, 8, 31), 4, 1, "finnhub"));

        var found = stockSplitRepository.findBySymbolAndEffectiveDate("AAPL", LocalDate.of(2020, 8, 31));

        assertThat(found).isPresent();
        assertThat(found.get().getNumerator()).isEqualTo(4);
        assertThat(found.get().getDenominator()).isEqualTo(1);
    }

    @Test
    void findBySymbolAndEffectiveDate_notFound_returnsEmpty() {
        var found = stockSplitRepository.findBySymbolAndEffectiveDate("TSLA", LocalDate.of(2020, 8, 31));

        assertThat(found).isEmpty();
    }

    // -------------------------------------------------------------------------
    // existsBySymbolAndEffectiveDate
    // -------------------------------------------------------------------------

    @Test
    void existsBySymbolAndEffectiveDate_exists_returnsTrue() {
        stockSplitRepository.save(new StockSplitEntity("NVDA", LocalDate.of(2021, 7, 20), 4, 1, "manual"));

        assertThat(stockSplitRepository.existsBySymbolAndEffectiveDate("NVDA", LocalDate.of(2021, 7, 20))).isTrue();
    }

    @Test
    void existsBySymbolAndEffectiveDate_notExists_returnsFalse() {
        assertThat(stockSplitRepository.existsBySymbolAndEffectiveDate("NOPE", LocalDate.of(2021, 7, 20))).isFalse();
    }

    // -------------------------------------------------------------------------
    // findBySymbolOrderByEffectiveDateAsc
    // -------------------------------------------------------------------------

    @Test
    void findBySymbolOrderByEffectiveDateAsc_returnsSplitsInOrder() {
        stockSplitRepository.save(new StockSplitEntity("AMZN", LocalDate.of(2022, 6, 6), 20, 1, "finnhub"));
        stockSplitRepository.save(new StockSplitEntity("AMZN", LocalDate.of(1998, 6, 2), 2, 1, "manual"));
        stockSplitRepository.save(new StockSplitEntity("AMZN", LocalDate.of(1999, 1, 5), 3, 1, "manual"));

        var splits = stockSplitRepository.findBySymbolOrderByEffectiveDateAsc("AMZN");

        assertThat(splits).hasSize(3);
        assertThat(splits.get(0).getEffectiveDate()).isEqualTo(LocalDate.of(1998, 6, 2));
        assertThat(splits.get(1).getEffectiveDate()).isEqualTo(LocalDate.of(1999, 1, 5));
        assertThat(splits.get(2).getEffectiveDate()).isEqualTo(LocalDate.of(2022, 6, 6));
    }

    @Test
    void findBySymbolOrderByEffectiveDateAsc_noSplits_returnsEmpty() {
        var splits = stockSplitRepository.findBySymbolOrderByEffectiveDateAsc("BND");

        assertThat(splits).isEmpty();
    }

    // -------------------------------------------------------------------------
    // findBySymbolsInAndDateRange — custom @Query
    // -------------------------------------------------------------------------

    @Test
    void findBySymbolsInAndDateRange_returnsOnlyMatchingSymbolsAndDates() {
        stockSplitRepository.save(new StockSplitEntity("AAPL", LocalDate.of(2020, 8, 31), 4, 1, "finnhub"));
        stockSplitRepository.save(new StockSplitEntity("TSLA", LocalDate.of(2020, 8, 31), 5, 1, "finnhub"));
        stockSplitRepository.save(new StockSplitEntity("GOOG", LocalDate.of(2022, 7, 18), 20, 1, "finnhub"));
        stockSplitRepository.save(new StockSplitEntity("AAPL", LocalDate.of(2015, 6, 9), 7, 1, "manual")); // outside range

        var splits = stockSplitRepository.findBySymbolsInAndDateRange(
                List.of("AAPL", "TSLA", "MSFT"),
                LocalDate.of(2020, 1, 1),
                LocalDate.of(2021, 12, 31));

        assertThat(splits).hasSize(2);
        assertThat(splits).extracting(StockSplitEntity::getSymbol)
                .containsExactlyInAnyOrder("AAPL", "TSLA");
    }

    @Test
    void findBySymbolsInAndDateRange_noMatchingSymbols_returnsEmpty() {
        stockSplitRepository.save(new StockSplitEntity("AAPL", LocalDate.of(2020, 8, 31), 4, 1, "finnhub"));

        var splits = stockSplitRepository.findBySymbolsInAndDateRange(
                List.of("MSFT", "GOOG"),
                LocalDate.of(2020, 1, 1),
                LocalDate.of(2021, 12, 31));

        assertThat(splits).isEmpty();
    }

    @Test
    void findBySymbolsInAndDateRange_resultsOrderedByEffectiveDateDesc() {
        stockSplitRepository.save(new StockSplitEntity("AAPL", LocalDate.of(2020, 8, 31), 4, 1, "finnhub"));
        stockSplitRepository.save(new StockSplitEntity("AAPL", LocalDate.of(2021, 3, 15), 2, 1, "manual"));

        var splits = stockSplitRepository.findBySymbolsInAndDateRange(
                List.of("AAPL"),
                LocalDate.of(2020, 1, 1),
                LocalDate.of(2021, 12, 31));

        assertThat(splits).hasSize(2);
        assertThat(splits.get(0).getEffectiveDate()).isAfterOrEqualTo(splits.get(1).getEffectiveDate());
    }

    // -------------------------------------------------------------------------
    // StockSplitAdjustmentRepository — tenant-scoped isolation
    // -------------------------------------------------------------------------

    @Test
    void findBySplitId_returnsAllAdjustmentsForSplit() {
        var split = stockSplitRepository.save(new StockSplitEntity("AAPL", LocalDate.of(2020, 8, 31), 4, 1, "finnhub"));

        stockSplitAdjustmentRepository.save(adj(split, tenantA.getId(), UUID.randomUUID()));
        stockSplitAdjustmentRepository.save(adj(split, tenantB.getId(), UUID.randomUUID()));

        var all = stockSplitAdjustmentRepository.findBySplit_Id(split.getId());

        assertThat(all).hasSize(2);
    }

    @Test
    void findBySplitIdAndTenantId_isolatesTenants() {
        var split = stockSplitRepository.save(new StockSplitEntity("TSLA", LocalDate.of(2020, 8, 31), 5, 1, "finnhub"));

        stockSplitAdjustmentRepository.save(adj(split, tenantA.getId(), UUID.randomUUID()));
        stockSplitAdjustmentRepository.save(adj(split, tenantB.getId(), UUID.randomUUID()));

        var forA = stockSplitAdjustmentRepository.findBySplit_IdAndTenantId(split.getId(), tenantA.getId());
        var forB = stockSplitAdjustmentRepository.findBySplit_IdAndTenantId(split.getId(), tenantB.getId());

        assertThat(forA).hasSize(1);
        assertThat(forA.get(0).getTenantId()).isEqualTo(tenantA.getId());
        assertThat(forB).hasSize(1);
        assertThat(forB.get(0).getTenantId()).isEqualTo(tenantB.getId());
    }

    @Test
    void findBySplitIdAndTenantId_queryForAbsentTenant_returnsEmpty() {
        var split = stockSplitRepository.save(new StockSplitEntity("NVDA", LocalDate.of(2021, 7, 20), 4, 1, "finnhub"));
        // Only Tenant A has an adjustment — querying for Tenant B should return nothing
        stockSplitAdjustmentRepository.save(adj(split, tenantA.getId(), UUID.randomUUID()));

        var result = stockSplitAdjustmentRepository.findBySplit_IdAndTenantId(split.getId(), tenantB.getId());

        assertThat(result).isEmpty();
    }

    @Test
    void countBySplitId_returnsCorrectCount() {
        var split = stockSplitRepository.save(new StockSplitEntity("GOOG", LocalDate.of(2022, 7, 18), 20, 1, "finnhub"));

        stockSplitAdjustmentRepository.save(adj(split, tenantA.getId(), UUID.randomUUID()));
        stockSplitAdjustmentRepository.save(adj(split, tenantA.getId(), UUID.randomUUID()));
        stockSplitAdjustmentRepository.save(adj(split, tenantB.getId(), UUID.randomUUID()));

        assertThat(stockSplitAdjustmentRepository.countBySplit_Id(split.getId())).isEqualTo(3);
    }

    @Test
    void countBySplitId_noAdjustments_returnsZero() {
        var split = stockSplitRepository.save(new StockSplitEntity("BND", LocalDate.of(2023, 1, 1), 2, 1, "manual"));

        assertThat(stockSplitAdjustmentRepository.countBySplit_Id(split.getId())).isZero();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static StockSplitAdjustmentEntity adj(StockSplitEntity split, UUID tenantId, UUID rowId) {
        return new StockSplitAdjustmentEntity(
                split, tenantId, "transactions", rowId, "quantity",
                new BigDecimal("10.0000"), new BigDecimal("40.0000"));
    }
}
