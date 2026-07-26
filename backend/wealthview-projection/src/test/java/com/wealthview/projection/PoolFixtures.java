package com.wealthview.projection;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import com.wealthview.core.projection.dto.HypotheticalAccountInput;
import com.wealthview.core.projection.dto.PoolType;
import com.wealthview.core.projection.dto.ProjectionAccountInput;
import com.wealthview.core.projection.strategy.WithdrawalOrder;
import com.wealthview.core.projection.tax.FilingStatus;
import com.wealthview.core.projection.tax.TaxCalculationStrategy;

import static com.wealthview.core.testutil.TaxBracketFixtures.bd;

/**
 * Shared {@code PoolStrategy.PoolConfig} / {@code PoolStrategy.MultiPool} fixtures, extracted from
 * near-identical private helpers duplicated across {@code MultiPoolDeepTest}, {@code
 * PoolStrategyTest}, {@code MultiPoolOwnerTest}, {@code MultiPoolCapitalGainsTest}, {@code
 * MultiPoolInterestYieldTest}, {@code PoolStrategyCharacterizationTest}, and {@code
 * RetirementTaxAnnotatorTest} (Task 22 audit: 37 raw {@code new PoolStrategy.PoolConfig(...)} call
 * sites across 9 files, 6 private {@code grouped()}/{@code pool()} clones).
 *
 * <p><strong>Package placement.</strong> This class lives in {@code com.wealthview.projection}
 * (NOT the usual {@code .testutil} sub-package) because {@code PoolStrategy} is a package-private
 * sealed interface -- {@code PoolStrategy.PoolConfig}, though itself an implicitly-public nested
 * record, cannot be named outside {@code com.wealthview.projection} since the enclosing interface
 * itself isn't visible there. Every consuming test class is already in this same package.
 *
 * <p>{@link #singleFilerConfig} composes {@code PoolStrategy.PoolConfig.builder(...)} (Task 14) --
 * its defaults (empty capital-market map, zero inflation/dividend/interest/fee, no LTCG/federal
 * calculator) are byte-identical to the 9-arg back-compat constructor every migrated call site used,
 * so this is a pure duplication-removal, not a behavior change.
 */
final class PoolFixtures {

    private PoolFixtures() {
    }

    /** Single filer, {@code "fixed"} conversion strategy, no tax calculator, no dynamic-sequencing rate. */
    static PoolStrategy.PoolConfig singleFilerConfig(WithdrawalOrder order) {
        return singleFilerConfig(order, null, null);
    }

    /** Single filer with a tax calculator wired in; no dynamic-sequencing bracket rate. */
    static PoolStrategy.PoolConfig singleFilerConfig(WithdrawalOrder order, TaxCalculationStrategy taxCalculator) {
        return singleFilerConfig(order, taxCalculator, null);
    }

    /** Single filer, fully parameterized: order, tax calculator, and dynamic-sequencing bracket rate. */
    static PoolStrategy.PoolConfig singleFilerConfig(WithdrawalOrder order, TaxCalculationStrategy taxCalculator,
                                                       BigDecimal dynamicSequencingBracketRate) {
        return PoolStrategy.PoolConfig.builder(FilingStatus.SINGLE, BigDecimal.ZERO, BigDecimal.ZERO, "fixed",
                        null, null, order, taxCalculator, dynamicSequencingBracketRate)
                .build();
    }

    /** Married-filing-jointly counterpart of {@link #singleFilerConfig(WithdrawalOrder)}. */
    static PoolStrategy.PoolConfig mfjConfig(WithdrawalOrder order) {
        return PoolStrategy.PoolConfig.builder(FilingStatus.MARRIED_FILING_JOINTLY, BigDecimal.ZERO,
                        BigDecimal.ZERO, "fixed", null, null, order, null, null)
                .build();
    }

    /**
     * Groups three pre-built accounts into the taxable/traditional/roth map {@code MultiPool}'s
     * constructor expects. Migrated verbatim from the byte-identical clone in {@code
     * MultiPoolCapitalGainsTest} and {@code MultiPoolInterestYieldTest}.
     */
    static Map<PoolType, List<ProjectionAccountInput>> grouped(HypotheticalAccountInput taxable,
                                                                 HypotheticalAccountInput traditional,
                                                                 HypotheticalAccountInput roth) {
        return Map.of(
                PoolType.TAXABLE, List.of(taxable),
                PoolType.TRADITIONAL, List.of(traditional),
                PoolType.ROTH, List.of(roth));
    }

    /**
     * A {@code MultiPool} built from three balance-only accounts (zero contribution, zero return
     * override) and {@link #singleFilerConfig(WithdrawalOrder)} -- zero growth under test. Migrated
     * verbatim from the byte-identical clone in {@code MultiPoolDeepTest.pool(...)} and {@code
     * PoolStrategyTest.multiPool(...)}.
     */
    static PoolStrategy.MultiPool multiPool(String taxable, String traditional, String roth, WithdrawalOrder order) {
        Map<PoolType, List<ProjectionAccountInput>> accounts = Map.of(
                PoolType.TAXABLE, List.of(new HypotheticalAccountInput(
                        bd(taxable), BigDecimal.ZERO, BigDecimal.ZERO, "taxable")),
                PoolType.TRADITIONAL, List.of(new HypotheticalAccountInput(
                        bd(traditional), BigDecimal.ZERO, BigDecimal.ZERO, "traditional")),
                PoolType.ROTH, List.of(new HypotheticalAccountInput(
                        bd(roth), BigDecimal.ZERO, BigDecimal.ZERO, "roth")));
        return new PoolStrategy.MultiPool(accounts, BigDecimal.ZERO, singleFilerConfig(order));
    }
}
