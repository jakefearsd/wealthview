package com.wealthview.projection;

import java.math.BigDecimal;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.wealthview.core.projection.dto.AssetAllocation;
import com.wealthview.core.projection.dto.HypotheticalAccountInput;
import com.wealthview.core.projection.strategy.WithdrawalOrder;
import com.wealthview.core.projection.tax.CapitalGainsTaxCalculator;
import com.wealthview.core.projection.tax.FederalOnlyTaxStrategy;
import com.wealthview.core.projection.tax.FederalTaxCalculator;
import com.wealthview.core.projection.tax.FilingStatus;
import com.wealthview.persistence.repository.LtcgBracketRepository;
import com.wealthview.persistence.repository.StandardDeductionRepository;
import com.wealthview.persistence.repository.TaxBracketRepository;

import static com.wealthview.core.testutil.TaxBracketFixtures.bd;
import static com.wealthview.core.testutil.TaxBracketFixtures.stubSingle2025;
import static com.wealthview.core.testutil.TaxBracketFixtures.stubSingle2025Ltcg;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.mock;

/**
 * D5 (Phase 1a): the gain realized by selling taxable lots to PAY tax is itself LTCG income, taxed in
 * the same year through the normal stacking -- previously discarded. Fixtures: single filer, 2025
 * ($15,000 standard deduction; 0% LTCG ceiling $48,350; 12% bracket ends $48,475; 22% to $103,350).
 */
class MultiPoolTaxSaleGainTest {

    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final int YEAR = 2025;
    private static final int AGE_RETIRED = 65;

    private static FederalTaxCalculator federal() {
        var brackets = mock(TaxBracketRepository.class);
        var deductions = mock(StandardDeductionRepository.class);
        stubSingle2025(brackets, deductions);
        return new FederalTaxCalculator(brackets, deductions);
    }

    private static CapitalGainsTaxCalculator capitalGains() {
        var repo = mock(LtcgBracketRepository.class);
        stubSingle2025Ltcg(repo);
        return new CapitalGainsTaxCalculator(repo);
    }

    private static HypotheticalAccountInput taxable(String balance, String basis) {
        return new HypotheticalAccountInput(bd(balance), ZERO, AssetAllocation.ALL_US,
                Optional.empty(), bd(basis), "taxable");
    }

    private static PoolStrategy.MultiPool pool(String taxableBalance, String taxableBasis,
                                               WithdrawalOrder order, String annualConversion) {
        var federal = federal();
        var config = PoolStrategy.PoolConfig.builder(FilingStatus.SINGLE, ZERO, bd(annualConversion), "fixed",
                        null, null, order, new FederalOnlyTaxStrategy(federal), null)
                .capitalGainsTaxCalculator(capitalGains())
                .federalTaxCalculator(federal)
                .baseYear(YEAR)
                .build();
        return new PoolStrategy.MultiPool(
                PoolFixtures.grouped(taxable(taxableBalance, taxableBasis),
                        new HypotheticalAccountInput(bd("500000"), ZERO, ZERO, "traditional"),
                        new HypotheticalAccountInput(ZERO, ZERO, ZERO, "roth")),
                ZERO, config);
    }

    @Test
    void executeWithdrawals_ordinaryTaxPaidFromGainLots_taxesTheSaleGainAtFifteenPercent() {
        // $70k traditional draw -> ordinary tax 7,014.00 (taxable 55,000). Paying it sells 50%-gain
        // lots; the gain stacks above the $48,350 0% ceiling -> 15%. Fixed point:
        // sale S = 7014 + 0.15 * 0.5 * S  ->  S = 7014 / 0.925 = 7582.70; LTCG = 568.70.
        var p = pool("200000", "100000", WithdrawalOrder.TRADITIONAL_FIRST, "0");

        var r = p.executeWithdrawals(bd("70000"), YEAR, ZERO, ZERO, ZERO, AGE_RETIRED);

        assertThat(r.ltcgTax()).isCloseTo(bd("568.70"), within(bd("0.01")));
        assertThat(r.taxLiability()).isCloseTo(bd("7582.70"), within(bd("0.01")));
        assertThat(r.realizedLtcgIncome()).isCloseTo(bd("3791.35"), within(bd("0.02")));
    }

    @Test
    void executeWithdrawals_ordinaryTaxPaidFromLossLots_lossOffsetsAndNeverCreatesNegativeTax() {
        // Same bill funded from a lot carrying a LOSS (basis 300k > value 200k): the realized loss
        // nets LTCG income to max(0, -3507) = 0 -> zero LTCG tax, and the ordinary bill is untouched.
        var p = pool("200000", "300000", WithdrawalOrder.TRADITIONAL_FIRST, "0");

        var r = p.executeWithdrawals(bd("70000"), YEAR, ZERO, ZERO, ZERO, AGE_RETIRED);

        assertThat(r.ltcgTax()).isEqualByComparingTo(ZERO);
        assertThat(r.realizedLtcgIncome()).isEqualByComparingTo(ZERO);
        assertThat(r.taxLiability()).isEqualByComparingTo(bd("7014.00"));
    }

    @Test
    void executeWithdrawals_noEmbeddedGain_billUnchangedFromPreD5() {
        // Basis == value: the funding sale realizes nothing -> exactly the pre-D5 ordinary bill.
        var p = pool("200000", "200000", WithdrawalOrder.TRADITIONAL_FIRST, "0");

        var r = p.executeWithdrawals(bd("70000"), YEAR, ZERO, ZERO, ZERO, AGE_RETIRED);

        assertThat(r.ltcgTax()).isEqualByComparingTo(ZERO);
        assertThat(r.taxLiability()).isEqualByComparingTo(bd("7014.00"));
    }

    @Test
    void conversionTaxSale_gainPendsUntilExecuteWithdrawalsThenIsTaxed() {
        // $80k fixed conversion -> tax 9,214.00 paid from 50%-gain lots -> pending gain 4,607.
        // executeWithdrawals (zero spend need) taxes it stacked on 80,000 - 15,000 = 65,000 (15%),
        // plus the LTCG bill's own funding sale: 691.05 / 0.925 = 747.08.
        var p = pool("200000", "100000", WithdrawalOrder.TAXABLE_FIRST, "80000");

        p.executeRothConversion(YEAR, ZERO, ZERO, ZERO);

        assertThat(p.hasPendingTaxSaleGain()).isTrue();

        var r = p.executeWithdrawals(ZERO, YEAR, ZERO, bd("80000"), ZERO, AGE_RETIRED);

        assertThat(r.ltcgTax()).isCloseTo(bd("747.08"), within(bd("0.01")));
        assertThat(r.realizedLtcgIncome()).isCloseTo(bd("4980.54"), within(bd("0.02")));
        assertThat(p.hasPendingTaxSaleGain()).isFalse();
    }

    @Test
    void snapshotRestore_pendingTaxSaleGainRestored() {
        var p = pool("200000", "100000", WithdrawalOrder.TAXABLE_FIRST, "80000");
        var memento = p.snapshot();
        p.executeRothConversion(YEAR, ZERO, ZERO, ZERO);

        p.restore(memento);

        assertThat(p.hasPendingTaxSaleGain()).isFalse();
    }

    @Test
    void executeWithdrawals_taxableCoversPartOfBillRestFromTraditional_gainPricedAndBillFullyFunded() {
        // Taxable (5,000 at 50% gain) cannot cover the ~7k bill, so it is sold in full (gain 2,500) and the
        // rest is a C2 traditional gross-up. The D5 sale-gain pricing and the C2 gross-up + LTCG re-stack
        // must coexist: the bill is exactly funded across the pools and exceeds the gain-free twin's.
        var gain = pool("5000", "2500", WithdrawalOrder.TRADITIONAL_FIRST, "0");
        var noGain = pool("5000", "5000", WithdrawalOrder.TRADITIONAL_FIRST, "0");

        var r = gain.executeWithdrawals(bd("70000"), YEAR, ZERO, ZERO, ZERO, AGE_RETIRED);
        var twin = noGain.executeWithdrawals(bd("70000"), YEAR, ZERO, ZERO, ZERO, AGE_RETIRED);

        BigDecimal funded = r.taxSource().fromTaxable().add(r.taxSource().fromTraditional())
                .add(r.taxSource().fromRoth());
        assertThat(funded).isEqualByComparingTo(r.taxLiability());
        assertThat(r.taxSource().fromTaxable()).isEqualByComparingTo(bd("5000"));
        assertThat(r.realizedLtcgIncome()).isEqualByComparingTo(bd("2500"));
        assertThat(r.taxLiability()).isGreaterThan(twin.taxLiability());
        assertThat(twin.ltcgTax()).isEqualByComparingTo(ZERO);
    }
}
