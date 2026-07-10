package com.wealthview.projection;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.wealthview.core.projection.dto.AssetAllocation;
import com.wealthview.core.projection.dto.HypotheticalAccountInput;
import com.wealthview.core.projection.dto.ProjectionAccountInput;
import com.wealthview.core.projection.strategy.WithdrawalOrder;
import com.wealthview.core.projection.tax.CapitalGainsTaxCalculator;
import com.wealthview.core.projection.tax.FilingStatus;
import com.wealthview.persistence.repository.LtcgBracketRepository;

import static com.wealthview.core.testutil.TaxBracketFixtures.bd;
import static com.wealthview.core.testutil.TaxBracketFixtures.stubSingle2025Ltcg;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Capital-gains behavior of {@link PoolStrategy.MultiPool}: FIFO realized-gain LTCG tax and the
 * annual dividend drag. Mirrors {@link MultiPoolDeepTest} construction but wires a real
 * {@link CapitalGainsTaxCalculator} (single filer, 2025 LTCG brackets: 0% ≤ $48,350, then 15%).
 */
class MultiPoolCapitalGainsTest {

    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final int YEAR = 2025;
    private static final int BASE_YEAR = 2025;
    private static final int AGE_RETIRED = 65;

    private static CapitalGainsTaxCalculator capitalGainsCalc() {
        var repo = mock(LtcgBracketRepository.class);
        stubSingle2025Ltcg(repo);
        return new CapitalGainsTaxCalculator(repo);
    }

    /** A taxable account carrying an explicit cost basis (may be below its balance = embedded gain). */
    private static HypotheticalAccountInput taxableAcct(String balance, String basis) {
        return new HypotheticalAccountInput(bd(balance), ZERO, AssetAllocation.ALL_US,
                Optional.empty(), bd(basis), "taxable");
    }

    private static HypotheticalAccountInput acct(String balance, String type) {
        return new HypotheticalAccountInput(bd(balance), ZERO, ZERO, type);
    }

    private static Map<String, List<ProjectionAccountInput>> grouped(
            HypotheticalAccountInput taxable, HypotheticalAccountInput traditional,
            HypotheticalAccountInput roth) {
        return Map.of(
                PoolStrategy.POOL_TAXABLE, List.of(taxable),
                PoolStrategy.POOL_TRADITIONAL, List.of(traditional),
                PoolStrategy.POOL_ROTH, List.of(roth));
    }

    private static PoolStrategy.PoolConfig config(String dividendYield, WithdrawalOrder order,
                                                   CapitalGainsTaxCalculator cg) {
        return new PoolStrategy.PoolConfig(
                FilingStatus.SINGLE, ZERO, ZERO, "fixed", null, null, order,
                null, null, Map.of(), ZERO, cg, bd(dividendYield), BASE_YEAR);
    }

    /** Per-pool returns: taxable grows at {@code taxableReturn}; traditional/roth flat. */
    private static PoolStrategy.MultiPool pool(HypotheticalAccountInput taxable,
                                                HypotheticalAccountInput traditional,
                                                HypotheticalAccountInput roth,
                                                String taxableReturn, String dividendYield,
                                                WithdrawalOrder order, CapitalGainsTaxCalculator cg) {
        return new PoolStrategy.MultiPool(
                grouped(taxable, traditional, roth),
                bd(taxableReturn), ZERO, ZERO, bd(taxableReturn),
                config(dividendYield, order, cg));
    }

    // ---- (a) embedded-gain taxable withdrawal now pays LTCG tax (was zero) ----

    @Test
    void executeWithdrawals_taxableDrawWithEmbeddedGain_paysLtcgTaxStackedOnOrdinaryIncome() {
        // Taxable $500k with $300k basis → $200k embedded gain. Ordinary income $60k pushes the
        // realized gain fully into the 15% LTCG bracket ($48,350 ceiling already exceeded).
        var pool = pool(taxableAcct("500000", "300000"), acct("0", "traditional"), acct("0", "roth"),
                "0", "0", WithdrawalOrder.TAXABLE_FIRST, capitalGainsCalc());

        // Draw $100k taxable-first. FIFO gain = 100000 × (500000-300000)/500000 = 40000.
        // Stacked on $60k ordinary → 40000 × 15% = 6000 LTCG tax. MAGI $100k < $200k → no NIIT.
        var r = pool.executeWithdrawals(bd("100000"), YEAR, bd("60000"), ZERO, ZERO, AGE_RETIRED);

        assertThat(r.fromTaxable()).isEqualByComparingTo(bd("100000"));
        assertThat(r.taxLiability()).isEqualByComparingTo(bd("6000"));
    }

    @Test
    void executeWithdrawals_taxableDrawNoEmbeddedGain_noLtcgTax() {
        // Basis == balance → zero realized gain; no dividend (yield 0) → no LTCG tax at all.
        var pool = pool(taxableAcct("500000", "500000"), acct("0", "traditional"), acct("0", "roth"),
                "0", "0", WithdrawalOrder.TAXABLE_FIRST, capitalGainsCalc());

        var r = pool.executeWithdrawals(bd("100000"), YEAR, bd("60000"), ZERO, ZERO, AGE_RETIRED);

        assertThat(r.fromTaxable()).isEqualByComparingTo(bd("100000"));
        assertThat(r.taxLiability()).isEqualByComparingTo(ZERO);
    }

    // ---- (b) low-income retiree pays 0% on the realized gain ----

    @Test
    void executeWithdrawals_embeddedGainButLowOrdinaryIncome_gainTaxedAtZeroPercent() {
        // Same $40k realized gain, but zero ordinary income → the whole gain sits in the 0% bracket
        // ($48,350 ceiling), so no LTCG tax is owed.
        var pool = pool(taxableAcct("500000", "300000"), acct("0", "traditional"), acct("0", "roth"),
                "0", "0", WithdrawalOrder.TAXABLE_FIRST, capitalGainsCalc());

        var r = pool.executeWithdrawals(bd("100000"), YEAR, ZERO, ZERO, ZERO, AGE_RETIRED);

        assertThat(r.fromTaxable()).isEqualByComparingTo(bd("100000"));
        assertThat(r.taxLiability()).isEqualByComparingTo(ZERO);
    }

    // ---- (c) dividend drag reduces the taxable pool while total return is preserved ----

    @Test
    void applyGrowthThenRothDraw_dividendDragTaxesReinvestedDividend_totalReturnPreserved() {
        // Taxable $100k at 6% return, 2% dividend yield. Roth-first withdrawal draws no taxable, so
        // the ONLY LTCG income is the reinvested qualified dividend.
        var pool = pool(taxableAcct("100000", "100000"), acct("0", "traditional"), acct("200000", "roth"),
                "0.06", "0.02", WithdrawalOrder.ROTH_FIRST, capitalGainsCalc());

        // Growth splits: lots appreciate at (6% − 2%) = 4% → 104000; dividend = 106000 − 104000 = 2000
        // reinvested at cost. Total taxable still grows at exactly 6% (100000 → 106000).
        var g = pool.applyGrowth();
        assertThat(g.taxable()).isEqualByComparingTo(bd("6000"));   // full 6% growth reported

        // Roth-first draw: no taxable sale (realized gain 0), but the $2000 dividend is LTCG income.
        // Ordinary $60k → dividend taxed at 15% = 300 (the drag = value × yield × ltcgRate).
        var r = pool.executeWithdrawals(bd("50000"), YEAR, bd("60000"), ZERO, ZERO, AGE_RETIRED);
        assertThat(r.fromTaxable()).isEqualByComparingTo(ZERO);
        assertThat(r.taxLiability()).isEqualByComparingTo(bd("300"));

        // The dividend tax drained the taxable pool from 106000 to 105700 (only the $300 drag).
        var dto = pool.buildYearDto(new PoolStrategy.YearDtoContext(YEAR, AGE_RETIRED, bd("300000"),
                ZERO, g.total(), bd("50000"), true, ZERO, r.taxLiability(), g,
                r.fromTaxable(), r.fromTraditional(), r.fromRoth(), r.taxSource()));
        assertThat(dto.taxableBalance()).isEqualByComparingTo(bd("105700"));
    }
}
