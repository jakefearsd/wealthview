package com.wealthview.projection;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.wealthview.core.projection.dto.GuardrailSpendingInput;
import com.wealthview.core.projection.dto.GuardrailYearlySpending;
import com.wealthview.core.projection.dto.ProjectionAccountInput;
import com.wealthview.core.projection.dto.ProjectionInput;
import com.wealthview.core.projection.dto.SpendingProfileInput;
import com.wealthview.core.projection.tax.CapitalGainsTaxCalculator;
import com.wealthview.core.projection.tax.FederalTaxCalculator;
import com.wealthview.core.projection.tax.FilingStatus;
import com.wealthview.core.projection.tax.SelfEmploymentTaxCalculator;
import com.wealthview.persistence.repository.LtcgBracketRepository;

import static com.wealthview.core.testutil.TaxBracketFixtures.bd;
import static com.wealthview.core.testutil.TaxBracketFixtures.stubSingle2025;
import static com.wealthview.core.testutil.TaxBracketFixtures.stubSingle2025Ltcg;
import static com.wealthview.projection.testutil.ProjectionTestFixtures.acct;
import static com.wealthview.projection.testutil.ProjectionTestFixtures.createInput;
import static com.wealthview.projection.testutil.ProjectionTestFixtures.createRetiredInput;
import static com.wealthview.projection.testutil.ProjectionTestFixtures.engineWithTax;
import static com.wealthview.projection.testutil.ProjectionTestFixtures.incomeSource;
import static com.wealthview.projection.testutil.ProjectionTestFixtures.selfEmploymentSource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class DeterministicProjectionEngineWithdrawalTest extends DeterministicProjectionEngineTestSupport {

    @Test
    void run_dynamicPercentageStrategy_withdrawsPercentOfCurrentBalance() {
        var input = createInput(
                LocalDate.now().minusYears(1), 90, bd("0.0300"),
                """
                {"birth_year": %d, "withdrawal_rate": 0.04, "withdrawal_strategy": "dynamic_percentage"}
                """.formatted(LocalDate.now().getYear() - 66),
                List.of(acct("1000000.0000", "0", "0.0500")));

        var result = engine.run(input);

        var year1 = result.yearlyData().getFirst();
        assertThat(year1.retired()).isTrue();
        // Real terms: 0.05 nominal override deflated at the scenario's own 3% inflation →
        // 1.05/1.03-1 = 0.01941748 real, minus the default 0.25% fee rate (audit B1; unset here) =
        // 0.01691748 net real. Year 1: $1M * 1.01691748 = $1,016,917.48; withdrawal = 4% of
        // currentBalance = $40,676.6992.
        assertThat(year1.withdrawals()).isEqualByComparingTo(bd("40676.6992"));

        // Year 2: dynamic_percentage recomputes 4% of currentBalance every year (not held constant),
        // so it compounds down at the same net real rate.
        var year2 = result.yearlyData().get(1);
        assertThat(year2.withdrawals()).isEqualByComparingTo(bd("39710.2526"));
    }

    @Test
    void run_vanguardStrategy_capsIncreasesAndFloorsDecreases() {
        var input = createInput(
                LocalDate.now().minusYears(1), 80, BigDecimal.ZERO,
                """
                {"birth_year": %d, "withdrawal_rate": 0.04, "withdrawal_strategy": "vanguard_dynamic_spending", "dynamic_ceiling": 0.05, "dynamic_floor": -0.025}
                """.formatted(LocalDate.now().getYear() - 66),
                List.of(acct("1000000.0000", "0", "0.0500")));

        var result = engine.run(input);

        // Real terms: 0% scenario inflation ⇒ 0.05 nominal override deflates to 5% real exactly,
        // minus the default 0.25% fee rate (audit B1; unset here) ⇒ 4.75% net real.
        // Year 1: $1M grows to $1,047,500; withdrawal = 4% = $41,900 (first retirement year: raw,
        // uncapped).
        var year1 = result.yearlyData().getFirst();
        assertThat(year1.withdrawals()).isEqualByComparingTo(bd("41900.0000"));

        // Year 2: balance $1,047,500 - $41,900 = $1,005,600 grows 4.75% to $1,053,366; raw = 4% =
        // $42,134.64, within [min $40,852.50, max $43,995] of the prior withdrawal → raw stands.
        var year2 = result.yearlyData().get(1);
        assertThat(year2.withdrawals()).isEqualByComparingTo(bd("42134.6400"));
    }

    @Test
    void run_noStrategySpecified_defaultsToFixedPercentage() {
        var input = createInput(
                LocalDate.now().minusYears(1), 90, bd("0.0300"),
                """
                {"birth_year": %d, "withdrawal_rate": 0.04}
                """.formatted(LocalDate.now().getYear() - 66),
                List.of(acct("1000000.0000", "0", "0.0500")));

        var result = engine.run(input);

        var year1 = result.yearlyData().getFirst();
        assertThat(year1.withdrawals()).isEqualByComparingTo(bd("40000.0000"));

        if (result.yearlyData().size() > 1) {
            var year2 = result.yearlyData().get(1);
            // Real terms: fixed-percentage withdrawal held constant real after year 1.
            assertThat(year2.withdrawals()).isEqualByComparingTo(bd("40000.0000"));
        }
    }

    @Test
    void run_withdrawalOrderTaxableFirst_drawsTaxableBeforeTraditional() {
        stubSingle2025(taxBracketRepository, standardDeductionRepository);
        var engineTax = engineWithTax(taxBracketRepository, standardDeductionRepository);

        var input = createInput(
                LocalDate.now().minusYears(1), 75, BigDecimal.ZERO,
                """
                {"birth_year": %d, "withdrawal_rate": 0.04, "filing_status": "single", "withdrawal_order": "taxable_first"}
                """.formatted(LocalDate.now().getYear() - 66),
                List.of(
                        acct("300000.0000", "0", "0.0500", "taxable"),
                        acct("200000.0000", "0", "0.0500", "traditional"),
                        acct("100000.0000", "0", "0.0500", "roth")));

        var result = engineTax.run(input);

        var year1 = result.yearlyData().getFirst();
        assertThat(year1.withdrawals()).isGreaterThan(BigDecimal.ZERO);
        assertThat(year1.taxableBalance()).isLessThan(bd("315000"));
        // Real terms: 0% scenario inflation ⇒ pools grow at the nominal 5% exactly, minus the
        // default 0.25% fee rate (audit B1; unset here); taxable-first leaves traditional untouched
        // at 200000 * (1.05 - 0.0025) = 209500.
        assertThat(year1.traditionalBalance()).isEqualByComparingTo(bd("209500.0000"));
    }

    @Test
    void run_withdrawalOrderTraditionalFirst_drawsTraditionalFirst() {
        stubSingle2025(taxBracketRepository, standardDeductionRepository);
        var engineTax = engineWithTax(taxBracketRepository, standardDeductionRepository);

        var input = createInput(
                LocalDate.now().minusYears(1), 75, BigDecimal.ZERO,
                """
                {"birth_year": %d, "withdrawal_rate": 0.04, "filing_status": "single", "withdrawal_order": "traditional_first"}
                """.formatted(LocalDate.now().getYear() - 66),
                List.of(
                        acct("300000.0000", "0", "0.0500", "taxable"),
                        acct("200000.0000", "0", "0.0500", "traditional"),
                        acct("100000.0000", "0", "0.0500", "roth")));

        var result = engineTax.run(input);

        var year1 = result.yearlyData().getFirst();
        assertThat(year1.traditionalBalance()).isLessThan(bd("210000"));
        BigDecimal tradReduction = bd("210000").subtract(year1.traditionalBalance());
        BigDecimal taxableReduction = bd("315000").subtract(year1.taxableBalance());
        assertThat(tradReduction).isGreaterThan(taxableReduction);
    }

    @Test
    void run_withdrawalOrderRothFirst_drawsRothFirst() {
        stubSingle2025(taxBracketRepository, standardDeductionRepository);
        var engineTax = engineWithTax(taxBracketRepository, standardDeductionRepository);

        var input = createInput(
                LocalDate.now().minusYears(1), 75, BigDecimal.ZERO,
                """
                {"birth_year": %d, "withdrawal_rate": 0.04, "filing_status": "single", "withdrawal_order": "roth_first"}
                """.formatted(LocalDate.now().getYear() - 66),
                List.of(
                        acct("300000.0000", "0", "0.0500", "taxable"),
                        acct("200000.0000", "0", "0.0500", "traditional"),
                        acct("100000.0000", "0", "0.0500", "roth")));

        var result = engineTax.run(input);

        var year1 = result.yearlyData().getFirst();
        // Real terms: 0% scenario inflation ⇒ pools grow at the nominal 5% exactly, minus the
        // default 0.25% fee rate (audit B1; unset here); roth-first leaves taxable/traditional
        // untouched at 300000 * (1.05 - 0.0025) = 314250 and 200000 * (1.05 - 0.0025) = 209500.
        assertThat(year1.taxableBalance()).isEqualByComparingTo(bd("314250.0000"));
        assertThat(year1.traditionalBalance()).isEqualByComparingTo(bd("209500.0000"));
        assertThat(year1.rothBalance()).isLessThan(bd("105000"));
    }

    @Test
    void run_withdrawalOrderProRata_withdrawsProportionally() {
        stubSingle2025(taxBracketRepository, standardDeductionRepository);
        var engineTax = engineWithTax(taxBracketRepository, standardDeductionRepository);

        var input = createInput(
                LocalDate.now().minusYears(1), 75, BigDecimal.ZERO,
                """
                {"birth_year": %d, "withdrawal_rate": 0.04, "filing_status": "single", "withdrawal_order": "pro_rata"}
                """.formatted(LocalDate.now().getYear() - 66),
                List.of(
                        acct("300000.0000", "0", "0.0500", "taxable"),
                        acct("200000.0000", "0", "0.0500", "traditional"),
                        acct("100000.0000", "0", "0.0500", "roth")));

        var result = engineTax.run(input);

        var year1 = result.yearlyData().getFirst();
        assertThat(year1.taxableBalance()).isLessThan(bd("315000"));
        assertThat(year1.traditionalBalance()).isLessThan(bd("210000"));
        assertThat(year1.rothBalance()).isLessThan(bd("105000"));
    }

    @Test
    void run_withdrawalOrderProRata_taxOnTraditionalPortion() {
        stubSingle2025(taxBracketRepository, standardDeductionRepository);
        var engineTax = engineWithTax(taxBracketRepository, standardDeductionRepository);

        // Use larger balances so traditional portion exceeds standard deduction
        var input = createInput(
                LocalDate.now().minusYears(1), 75, BigDecimal.ZERO,
                """
                {"birth_year": %d, "withdrawal_rate": 0.10, "filing_status": "single", "withdrawal_order": "pro_rata"}
                """.formatted(LocalDate.now().getYear() - 66),
                List.of(
                        acct("300000.0000", "0", "0.0500", "taxable"),
                        acct("500000.0000", "0", "0.0500", "traditional"),
                        acct("100000.0000", "0", "0.0500", "roth")));

        var result = engineTax.run(input);

        var year1 = result.yearlyData().getFirst();
        assertThat(year1.taxLiability()).isNotNull();
        assertThat(year1.taxLiability()).isGreaterThan(BigDecimal.ZERO);
    }

    @Test
    void run_taxableFirstWithdrawal_noTaxOnTaxableWithdrawals() {
        stubSingle2025(taxBracketRepository, standardDeductionRepository);
        var engineTax = engineWithTax(taxBracketRepository, standardDeductionRepository);

        var input = createInput(
                LocalDate.now().minusYears(1), 75, BigDecimal.ZERO,
                """
                {"birth_year": %d, "withdrawal_rate": 0.04, "filing_status": "single", "withdrawal_order": "taxable_first"}
                """.formatted(LocalDate.now().getYear() - 66),
                List.of(
                        acct("300000.0000", "0", "0.0500", "taxable"),
                        acct("200000.0000", "0", "0.0500", "traditional"),
                        acct("100000.0000", "0", "0.0500", "roth")));

        var result = engineTax.run(input);

        var year1 = result.yearlyData().getFirst();
        assertThat(year1.taxableBalance()).isLessThan(bd("315000"));
        // Real terms: 0% scenario inflation ⇒ pools grow at the nominal 5% exactly, minus the
        // default 0.25% fee rate (audit B1; unset here); taxable-first leaves traditional untouched
        // at 200000 * (1.05 - 0.0025) = 209500.
        assertThat(year1.traditionalBalance()).isEqualByComparingTo(bd("209500.0000"));
    }

    @Test
    void run_retirementWithdrawal_exposesPerPoolWithdrawals() {
        stubSingle2025(taxBracketRepository, standardDeductionRepository);
        var engineTax = engineWithTax(taxBracketRepository, standardDeductionRepository);

        var input = createInput(
                LocalDate.now().minusYears(1), 75, BigDecimal.ZERO,
                """
                {"birth_year": %d, "filing_status": "single", "withdrawal_rate": 0.04}
                """.formatted(LocalDate.now().getYear() - 66),
                List.of(
                        acct("300000.0000", "0", "0.0500", "traditional"),
                        acct("100000.0000", "0", "0.0500", "roth"),
                        acct("100000.0000", "0", "0.0500", "taxable")));

        var result = engineTax.run(input);

        var year1 = result.yearlyData().getFirst();
        assertThat(year1.withdrawals()).isGreaterThan(BigDecimal.ZERO);
        // Default order is taxable-first
        assertThat(year1.withdrawalFromTaxable()).isNotNull();
        // Per-pool withdrawals should sum to total withdrawals
        BigDecimal totalPerPool = (year1.withdrawalFromTaxable() != null ? year1.withdrawalFromTaxable() : BigDecimal.ZERO)
                .add(year1.withdrawalFromTraditional() != null ? year1.withdrawalFromTraditional() : BigDecimal.ZERO)
                .add(year1.withdrawalFromRoth() != null ? year1.withdrawalFromRoth() : BigDecimal.ZERO);
        assertThat(totalPerPool).isEqualByComparingTo(year1.withdrawals());
    }

    @Test
    void run_proRata_unevenBalances_withdrawalsSumToNeed() {
        stubSingle2025(taxBracketRepository, standardDeductionRepository);
        var engineTax = engineWithTax(taxBracketRepository, standardDeductionRepository);

        var input = createRetiredInput(
                """
                {"birth_year": %d, "withdrawal_rate": 0.04, "filing_status": "single",
                 "withdrawal_order": "pro_rata"}
                """.formatted(LocalDate.now().getYear() - 66),
                List.of(
                        acct("333000", "0", "0.00", "traditional"),
                        acct("222000", "0", "0.00", "roth"),
                        acct("111000", "0", "0.00", "taxable")));

        var result = engineTax.run(input);
        var year1 = result.yearlyData().getFirst();

        // Total = $666K, withdrawal = 4% = $26,640
        assertThat(year1.withdrawals()).isEqualByComparingTo(bd("26640"));

        // Sum of per-pool withdrawals must equal total
        BigDecimal sumPools = year1.withdrawalFromTaxable()
                .add(year1.withdrawalFromTraditional())
                .add(year1.withdrawalFromRoth());
        assertThat(sumPools).isEqualByComparingTo(year1.withdrawals());

        // Each pool's share should be proportional (within rounding)
        // traditional: 333/666 = 50%, roth: 222/666 = 33.3%, taxable: 111/666 = 16.7%
        assertThat(year1.withdrawalFromTraditional().divide(year1.withdrawals(), 2, java.math.RoundingMode.HALF_UP))
                .isEqualByComparingTo(bd("0.50"));
    }

    // === Vanguard dynamic spending floor ===

    @Test
    void run_vanguardFloor_severeMarketLoss_floorsWithdrawal() {
        // Vanguard uses currentBalance (after growth), not startOfYearBalance.
        // Real terms: 0% scenario inflation ⇒ -0.30 nominal override deflates to -30% real, minus
        // the default 0.25% fee rate (audit B1; unset here) ⇒ -30.25% net real.
        // Year 1: $1M * 0.6975 = $697,500. Raw = * 0.04 = $27,900.
        // Year 2 raw collapses far below the floor, so the floor binds:
        // floor = $27,900 * (1 - 0.025) = $27,202.50 → withdrawal capped UP to the floor.
        var input = createInput(
                LocalDate.now().minusYears(1), 75, BigDecimal.ZERO,
                """
                {"birth_year": %d, "withdrawal_rate": 0.04,
                 "withdrawal_strategy": "vanguard_dynamic_spending",
                 "dynamic_ceiling": 0.05, "dynamic_floor": -0.025}
                """.formatted(LocalDate.now().getYear() - 66),
                List.of(acct("1000000", "0", "-0.30")));

        var result = engine.run(input);
        var year1 = result.yearlyData().get(0);
        var year2 = result.yearlyData().get(1);

        // Year 1: raw = currentBalance * 0.04 = $697,500 * 0.04 = $27,900
        assertThat(year1.withdrawals()).isEqualByComparingTo(bd("27900.0000"));

        // Year 2: raw far below floor → capped at floor = 27900 * 0.975 = 27202.50
        assertThat(year2.withdrawals()).isEqualByComparingTo(bd("27202.5000"));

        // Floor prevented a severe drop — withdrawal only dropped 2.5%
        assertThat(year2.withdrawals()).isGreaterThan(year1.withdrawals().multiply(bd("0.95")));
    }

    // === Dynamic Sequencing withdrawal tests ===

    @Test
    void run_dynamicSequencing_drawsTraditionalUpToBracketCeilingThenTaxableThenRoth() {
        stubSingle2025(taxBracketRepository, standardDeductionRepository);
        var engineTax = engineWithTax(taxBracketRepository, standardDeductionRepository);

        int retireAge = 66;
        int birthYear = LocalDate.now().getYear() - retireAge;

        // Traditional $500K, Taxable $200K, Roth $100K
        // 0% return, 0% inflation → predictable amounts
        // withdrawal_rate=0.10 → need = 10% of $800K = $80K
        // 12% bracket ceiling for single: $48,475 taxable + $15,000 std deduction = $63,475 gross
        // bracketSpace = $63,475 - $0(other) - $0(conversion) - $0(rmd) = $63,475
        // fromTraditional = min($63,475, $500K, $80K) = $63,475
        // remaining = $80K - $63,475 = $16,525
        // fromTaxable = min($16,525, $200K) = $16,525
        // fromRoth = $0
        var input = createRetiredInput(
                """
                {"birth_year": %d, "withdrawal_rate": 0.10, "filing_status": "single",
                 "withdrawal_order": "dynamic_sequencing", "dynamic_sequencing_bracket_rate": 0.12}
                """.formatted(birthYear),
                List.of(
                        acct("500000", "0", "0.00", "traditional"),
                        acct("200000", "0", "0.00", "taxable"),
                        acct("100000", "0", "0.00", "roth")));

        var result = engineTax.run(input);
        var year1 = result.yearlyData().getFirst();

        // Traditional should be drawn up to bracket ceiling ($63,475)
        assertThat(year1.withdrawalFromTraditional()).isNotNull();
        assertThat(year1.withdrawalFromTraditional()).isEqualByComparingTo(bd("63475"));

        // Taxable covers the remainder ($80K - $63,475 = $16,525)
        assertThat(year1.withdrawalFromTaxable()).isNotNull();
        assertThat(year1.withdrawalFromTaxable()).isEqualByComparingTo(bd("16525"));

        // Roth should not be touched
        assertThat(year1.withdrawalFromRoth()).isNull();
    }

    @Test
    void run_dynamicSequencing_traditionalLessThanBracketSpace_drawsAllTraditionalThenTaxable() {
        stubSingle2025(taxBracketRepository, standardDeductionRepository);
        var engineTax = engineWithTax(taxBracketRepository, standardDeductionRepository);

        int retireAge = 66;
        int birthYear = LocalDate.now().getYear() - retireAge;

        // Traditional $30K (less than bracket space of $63,475)
        // Need = 10% of $330K = $33K
        // bracketSpace = $63,475
        // fromTraditional = min($63,475, $30K, $33K) = $30K
        // remaining = $33K - $30K = $3K
        // fromTaxable = min($3K, $200K) = $3K
        // fromRoth = $0
        // fee_rate pinned to 0 (audit B1) -- otherwise the default 0.25% drag would shrink the
        // "flat at 0% return" traditional pool this test relies on to isolate the dynamic-sequencing
        // bracket-space arithmetic from growth.
        var input = createRetiredInput(
                """
                {"birth_year": %d, "withdrawal_rate": 0.10, "filing_status": "single",
                 "withdrawal_order": "dynamic_sequencing", "dynamic_sequencing_bracket_rate": 0.12,
                 "fee_rate": 0}
                """.formatted(birthYear),
                List.of(
                        acct("30000", "0", "0.00", "traditional"),
                        acct("200000", "0", "0.00", "taxable"),
                        acct("100000", "0", "0.00", "roth")));

        var result = engineTax.run(input);
        var year1 = result.yearlyData().getFirst();

        // All traditional should be drawn (< bracket space). Real terms: 0% scenario inflation ⇒ the
        // 0.00 nominal override deflates to 0% real exactly, so the $30K pool stays flat at $30,000.
        assertThat(year1.withdrawalFromTraditional()).isNotNull();
        assertThat(year1.withdrawalFromTraditional()).isEqualByComparingTo(bd("30000.0000"));

        // Taxable covers the remainder. Need = 10% of $330K start = $33K; 33000 - 30000 = 3000.
        assertThat(year1.withdrawalFromTaxable()).isNotNull();
        assertThat(year1.withdrawalFromTaxable()).isEqualByComparingTo(bd("3000.0000"));

        // Roth should not be touched
        assertThat(year1.withdrawalFromRoth()).isNull();
    }

    @Test
    void run_dynamicSequencing_conversionExceedsBracket_traditionalWithdrawalIsZero() {
        stubSingle2025(taxBracketRepository, standardDeductionRepository);
        var engineTax = engineWithTax(taxBracketRepository, standardDeductionRepository);

        int retireAge = 66;
        int birthYear = LocalDate.now().getYear() - retireAge;

        // Conversion of $70K exceeds bracket ceiling of $63,475
        // bracketSpace = max($63,475 - $0 - $70K - $0, 0) = $0
        // fromTraditional = min($0, $500K, need) = $0
        // All withdrawal should come from taxable
        var input = createRetiredInput(
                """
                {"birth_year": %d, "withdrawal_rate": 0.04, "filing_status": "single",
                 "withdrawal_order": "dynamic_sequencing", "dynamic_sequencing_bracket_rate": 0.12,
                 "annual_roth_conversion": 70000}
                """.formatted(birthYear),
                List.of(
                        acct("500000", "0", "0.00", "traditional"),
                        acct("200000", "0", "0.00", "taxable"),
                        acct("100000", "0", "0.00", "roth")));

        var result = engineTax.run(input);
        var year1 = result.yearlyData().getFirst();

        // Conversion consumed all bracket space → no traditional withdrawal
        assertThat(year1.withdrawalFromTraditional()).isNull();

        // All withdrawal from taxable
        assertThat(year1.withdrawalFromTaxable()).isNotNull();
        assertThat(year1.withdrawalFromTaxable()).isGreaterThan(BigDecimal.ZERO);

        // Roth should not be touched for withdrawals
        assertThat(year1.withdrawalFromRoth()).isNull();
    }

    @Test
    void run_dynamicSequencing_beforeAge60_taxableOnlyRegardlessOfDS() {
        stubSingle2025(taxBracketRepository, standardDeductionRepository);
        var engineTax = engineWithTax(taxBracketRepository, standardDeductionRepository);

        // Age 55 at retirement → before 59.5 threshold (use 60 as proxy)
        int retireAge = 55;
        int birthYear = LocalDate.now().getYear() - retireAge;

        // Even with DS configured, before age 60, only taxable should be drawn
        var input = createInput(
                LocalDate.now().minusYears(1), 75, BigDecimal.ZERO,
                """
                {"birth_year": %d, "withdrawal_rate": 0.04, "filing_status": "single",
                 "withdrawal_order": "dynamic_sequencing", "dynamic_sequencing_bracket_rate": 0.12}
                """.formatted(birthYear),
                List.of(
                        acct("500000", "0", "0.00", "traditional"),
                        acct("200000", "0", "0.00", "taxable"),
                        acct("100000", "0", "0.00", "roth")));

        var result = engineTax.run(input);
        var year1 = result.yearlyData().getFirst();

        // Before age 60: only taxable should be drawn, traditional and roth untouched
        assertThat(year1.withdrawalFromTaxable()).isNotNull();
        assertThat(year1.withdrawalFromTaxable()).isGreaterThan(BigDecimal.ZERO);
        assertThat(year1.withdrawalFromTraditional()).isNull();
        assertThat(year1.withdrawalFromRoth()).isNull();
    }

    // === RMD forcing (main projection) ===

    @Test
    void run_traditionalHeavyRetireeAtRmdAge_forcesRmdAndTaxesIt() {
        stubSingle2025(taxBracketRepository, standardDeductionRepository);
        var engineTax = engineWithTax(taxBracketRepository, standardDeductionRepository);

        int rmdStartAge = 73; // SECURE 2.0 start age for birth years well before 1960
        // Pinned rather than derived from LocalDate.now(): a wall-clock-relative birth year
        // (currentYear - rmdStartAge) crosses the SECURE-2.0 threshold in 2033, at which point
        // birthYear would land on/after 1960 and rmdStartAge would silently become 75, breaking
        // this test's assumptions. A fixed referenceYear + a birth year well before 1960 keeps the
        // age arithmetic and the rmdStartAge=73 assumption stable regardless of the wall clock.
        int referenceYear = 2025;
        int rmdBirthYear = 1952;         // age 73 at referenceYear -- exactly rmdStartAge
        int noRmdBirthYear = 1953;       // age 72 at referenceYear -- one below rmdStartAge
        LocalDate retirementDate = LocalDate.of(2020, 1, 1); // comfortably before referenceYear

        // Traditional-heavy retiree, already retired, taxable-first order and 0% returns so the
        // small spend need is fully covered by taxable and every figure is exact -- isolating the
        // RMD's effect from both growth and the withdrawal-order mechanics.
        List<ProjectionAccountInput> accounts = List.of(
                acct("1000000.0000", "0", "0.0000", "traditional"),
                acct("500000.0000", "0", "0.0000", "taxable"));

        // fee_rate pinned to 0 (audit B1) on both runs -- otherwise the default 0.25% drag would
        // shrink the "flat at 0% return" pools this test relies on to isolate the RMD math.
        var rmdInput = createInput(
                retirementDate, 95, BigDecimal.ZERO,
                """
                {"birth_year": %d, "withdrawal_rate": 0.01, "filing_status": "single", "withdrawal_order": "taxable_first", "fee_rate": 0}
                """.formatted(rmdBirthYear),
                accounts, null, referenceYear, List.of());
        // Same scenario one birth-year younger: age is one below the RMD start age, so rmdAmount
        // stays zero and this run isolates what the year would have looked like without the RMD.
        var noRmdInput = createInput(
                retirementDate, 95, BigDecimal.ZERO,
                """
                {"birth_year": %d, "withdrawal_rate": 0.01, "filing_status": "single", "withdrawal_order": "taxable_first", "fee_rate": 0}
                """.formatted(noRmdBirthYear),
                accounts, null, referenceYear, List.of());

        var rmdYear = engineTax.run(rmdInput).yearlyData().getFirst();
        var noRmdYear = engineTax.run(noRmdInput).yearlyData().getFirst();

        assertThat(rmdYear.age()).isEqualTo(rmdStartAge);
        assertThat(noRmdYear.age()).isEqualTo(rmdStartAge - 1);

        // Spend need (1% of $1.5M start balance = $15,000) is fully covered by taxable, so absent
        // an RMD, traditional stays untouched -- confirmed by the one-year-younger comparison run.
        // taxLiability is nulled by the DTO builder's "positive value or null" convention when zero.
        assertThat(noRmdYear.traditionalBalance()).isEqualByComparingTo(bd("1000000.0000"));
        assertThat(noRmdYear.taxLiability()).isNull();

        // RMD = priorYearEndTraditional (1,000,000) / distributionPeriod(73)=26.5 = 37,735.8491,
        // forced out of traditional on top of the (fully taxable-covered) spend draw.
        BigDecimal expectedRmd = bd("37735.8491");
        assertThat(rmdYear.traditionalBalance()).isEqualByComparingTo(bd("1000000.0000").subtract(expectedRmd));

        BigDecimal tradDrop = noRmdYear.traditionalBalance().subtract(rmdYear.traditionalBalance());
        assertThat(tradDrop).isEqualByComparingTo(expectedRmd);

        // The gross RMD excess is reinvested to taxable: taxable grows beyond the post-spend base
        // ($500,000 - $15,000 = $485,000) by the RMD, net of the tax paid on it from that same pool.
        assertThat(rmdYear.taxableBalance()).isGreaterThan(bd("485000.0000"));
        assertThat(rmdYear.taxableBalance()).isLessThan(bd("485000.0000").add(expectedRmd));

        // RMD income is real ordinary income, taxed unlike the no-RMD comparison year (null == $0).
        assertThat(rmdYear.taxLiability()).isGreaterThan(BigDecimal.ZERO);
    }

    // === Audit A2: income-covered years must still force RMDs and tax dividends ===

    /**
     * A real {@link FederalTaxCalculator} backed by the SAME mocked brackets/deduction repos the
     * scenario's engine uses, so tests can compute an authoritative expected tax figure by calling
     * the production tax class directly instead of hand-deriving bracket arithmetic. With no state
     * calculator wired (the {@code engineWithTax}/no-state-factory path), {@code
     * CombinedTaxCalculator.computeTax(...).totalTax()} reduces exactly to
     * {@code FederalTaxCalculator.computeTax(...)} (state tax 0, standard deduction always wins over
     * a $0 itemized total) -- so this is a faithful stand-in, not an approximation.
     */
    private FederalTaxCalculator referenceFederalCalc() {
        return new FederalTaxCalculator(taxBracketRepository, standardDeductionRepository);
    }

    @Test
    void run_incomeCoveredRetireeAtRmdAge_forcesRmdAndTaxesItWithoutDoubleCountingPensionTax() {
        stubSingle2025(taxBracketRepository, standardDeductionRepository);
        var engineTax = engineWithTax(taxBracketRepository, standardDeductionRepository);

        // Pinned rather than derived from LocalDate.now() -- see the identical rationale on
        // run_traditionalHeavyRetireeAtRmdAge_forcesRmdAndTaxesIt above.
        int referenceYear = 2025;
        int rmdBirthYear = 1952;    // age 73 at referenceYear -- exactly rmdStartAge
        int noRmdBirthYear = 1953;  // age 72 at referenceYear -- one below rmdStartAge
        LocalDate retirementDate = LocalDate.of(2020, 1, 1); // comfortably before referenceYear

        // Pension income ($80,000) fully covers spending ($40,000): the SpendingPlan surplus branch
        // resolves portfolioNeed to zero (see RetirementWithdrawalProcessor#process), which used to
        // make MultiPool.executeWithdrawals early-return before ever forcing the RMD or taxing it.
        // Taxable starts at $0 so this year's income-covered surplus deposit and the RMD's own
        // reinvestment are the only two things that can land in it -- isolating the RMD's effect.
        List<ProjectionAccountInput> accounts = List.of(
                acct("1000000.0000", "0", "0.0000", "traditional"),
                acct("0.0000", "0", "0.0000", "taxable"));
        var spending = new SpendingProfileInput(bd("30000"), bd("10000"), null);
        var pension = incomeSource("Pension", "80000", 60, null, "0");

        // fee_rate pinned to 0 (audit B1) on both runs -- otherwise the default 0.25% drag would
        // shrink the "flat at 0% return" traditional pool this test relies on to isolate the RMD
        // math from growth.
        var rmdInput = createInput(
                retirementDate, 95, BigDecimal.ZERO,
                """
                {"birth_year": %d, "filing_status": "single", "fee_rate": 0}
                """.formatted(rmdBirthYear),
                accounts, spending, referenceYear, List.of(pension));
        // Same scenario one birth-year younger: age is one below the RMD start age, so rmdAmount
        // stays zero and this run isolates what the year would have looked like without the RMD
        // (income/spending/surplus mechanics are otherwise identical).
        var noRmdInput = createInput(
                retirementDate, 95, BigDecimal.ZERO,
                """
                {"birth_year": %d, "filing_status": "single", "fee_rate": 0}
                """.formatted(noRmdBirthYear),
                accounts, spending, referenceYear, List.of(pension));

        var rmdYear = engineTax.run(rmdInput).yearlyData().getFirst();
        var noRmdYear = engineTax.run(noRmdInput).yearlyData().getFirst();

        assertThat(rmdYear.age()).isEqualTo(73);
        assertThat(noRmdYear.age()).isEqualTo(72);
        assertThat(rmdYear.withdrawals()).isEqualByComparingTo(BigDecimal.ZERO); // no spending draw
        assertThat(noRmdYear.rmdAmount()).isNull();

        // RMD = priorYearEndTraditional (1,000,000) / distributionPeriod(73)=26.5 = 37,735.8491,
        // forced out of traditional even though the pension alone covers spending.
        BigDecimal expectedRmd = bd("37735.8491");
        assertThat(rmdYear.rmdAmount()).isEqualByComparingTo(expectedRmd);
        assertThat(rmdYear.traditionalBalance()).isEqualByComparingTo(bd("1000000.0000").subtract(expectedRmd));

        // taxLiability must equal the FULL combined tax on (RMD + pension) -- not that plus a
        // second, redundant tax on the pension alone (the double-count this fix must avoid).
        var refCalc = referenceFederalCalc();
        BigDecimal pensionTaxAlone = refCalc.computeTax(bd("80000"), referenceYear, FilingStatus.SINGLE);
        BigDecimal combinedTax = refCalc.computeTax(bd("80000").add(expectedRmd), referenceYear, FilingStatus.SINGLE);

        assertThat(noRmdYear.taxLiability()).isEqualByComparingTo(pensionTaxAlone);
        assertThat(rmdYear.taxLiability()).isEqualByComparingTo(combinedTax);
        // Sanity: the RMD really did add incremental tax on top of the pension-alone baseline.
        assertThat(combinedTax).isGreaterThan(pensionTaxAlone);

        // The RMD's gross proceeds land in taxable and its tax is paid out of that same pool, so the
        // taxable-balance DELTA between the two runs (surplus-deposit mechanics are identical in
        // both) is exactly the after-tax RMD remainder.
        BigDecimal marginalRmdTax = combinedTax.subtract(pensionTaxAlone);
        BigDecimal expectedAfterTaxRmd = expectedRmd.subtract(marginalRmdTax);
        BigDecimal taxableDelta = rmdYear.taxableBalance().subtract(noRmdYear.taxableBalance());
        assertThat(taxableDelta).isEqualByComparingTo(expectedAfterTaxRmd);
        assertThat(taxableDelta).isPositive();
        assertThat(taxableDelta).isLessThan(expectedRmd);
    }

    @Test
    void run_incomeCoveredRetireeWithTaxableDividends_paysCapitalGainsTaxOnZeroSpendDraw() {
        stubSingle2025(taxBracketRepository, standardDeductionRepository);
        var ltcgBracketRepo = mock(LtcgBracketRepository.class);
        stubSingle2025Ltcg(ltcgBracketRepo);
        var engineTax = new DeterministicProjectionEngine(
                referenceFederalCalc(), null, new CapitalGainsTaxCalculator(ltcgBracketRepo));

        int referenceYear = 2025;
        int birthYear = 1955; // age 70 -- retired, no traditional pool at all so RMD is a non-factor
        LocalDate retirementDate = LocalDate.of(2020, 1, 1);

        // No traditional account: taxable + roth only. Pension covers spending (zero portfolio
        // need), so the only ordinary income this year is the pension -- comfortably above the
        // $48,350 single 0% LTCG ceiling once netted by the $15,000 standard deduction, so the
        // taxable pool's qualified dividend (default 1.8% yield -> $50,000 x 0.018 = $900) stacks
        // entirely into the 15% LTCG bracket instead of the 0% one.
        List<ProjectionAccountInput> accounts = List.of(
                acct("50000.0000", "0", "0.0000", "taxable"),
                acct("50000.0000", "0", "0.0000", "roth"));
        var spending = new SpendingProfileInput(bd("30000"), bd("10000"), null);
        var pension = incomeSource("Pension", "80000", 60, null, "0");

        var input = createInput(
                retirementDate, 95, BigDecimal.ZERO,
                """
                {"birth_year": %d, "filing_status": "single"}
                """.formatted(birthYear),
                accounts, spending, referenceYear, List.of(pension));

        var year1 = engineTax.run(input).yearlyData().getFirst();

        assertThat(year1.withdrawals()).isEqualByComparingTo(BigDecimal.ZERO); // zero spend draw
        assertThat(year1.rmdAmount()).isNull(); // no traditional pool
        assertThat(year1.capitalGainsTax()).isNotNull();
        assertThat(year1.capitalGainsTax()).isGreaterThan(BigDecimal.ZERO);

        // No double-counting: taxLiability is exactly the pension's ordinary tax plus the LTCG tax
        // the DTO reports, not some larger or smaller figure.
        BigDecimal pensionTaxAlone = referenceFederalCalc().computeTax(bd("80000"), referenceYear, FilingStatus.SINGLE);
        assertThat(year1.taxLiability()).isEqualByComparingTo(pensionTaxAlone.add(year1.capitalGainsTax()));
    }

    @Test
    void run_incomeCoveredRetireeBelowRmdAge_noRmdButDividendStillTaxed() {
        stubSingle2025(taxBracketRepository, standardDeductionRepository);
        var ltcgBracketRepo = mock(LtcgBracketRepository.class);
        stubSingle2025Ltcg(ltcgBracketRepo);
        var engineTax = new DeterministicProjectionEngine(
                referenceFederalCalc(), null, new CapitalGainsTaxCalculator(ltcgBracketRepo));

        int referenceYear = 2025;
        int noRmdBirthYear = 1953; // age 72 -- one year below rmdStartAge 73
        LocalDate retirementDate = LocalDate.of(2020, 1, 1);

        // Traditional pool present (so an RMD would fire if age qualified) plus a taxable pool for
        // the dividend, pension covering spending exactly as in the sibling tests above.
        List<ProjectionAccountInput> accounts = List.of(
                acct("1000000.0000", "0", "0.0000", "traditional"),
                acct("50000.0000", "0", "0.0000", "taxable"));
        var spending = new SpendingProfileInput(bd("30000"), bd("10000"), null);
        var pension = incomeSource("Pension", "80000", 60, null, "0");

        // fee_rate pinned to 0 (audit B1) -- otherwise the default 0.25% drag would shrink the
        // "flat at 0% return, untouched" traditional pool this test asserts against.
        var input = createInput(
                retirementDate, 95, BigDecimal.ZERO,
                """
                {"birth_year": %d, "filing_status": "single", "fee_rate": 0}
                """.formatted(noRmdBirthYear),
                accounts, spending, referenceYear, List.of(pension));

        var year1 = engineTax.run(input).yearlyData().getFirst();

        assertThat(year1.age()).isEqualTo(72);
        assertThat(year1.withdrawals()).isEqualByComparingTo(BigDecimal.ZERO);
        // No RMD: below rmdStartAge, and the traditional balance is untouched.
        assertThat(year1.rmdAmount()).isNull();
        assertThat(year1.traditionalBalance()).isEqualByComparingTo(bd("1000000.0000"));
        // Dividend on the $50,000 taxable pool is still taxed even though nothing forced a draw.
        assertThat(year1.capitalGainsTax()).isNotNull();
        assertThat(year1.capitalGainsTax()).isGreaterThan(BigDecimal.ZERO);
    }

    // === Audit A4: tax on outside income must be a FUNDED outflow ===

    @Test
    void run_surplusYearTaxExceedsSurplus_fundsRemainderFromPoolsAndBalanceIdentityHolds() {
        stubSingle2025(taxBracketRepository, standardDeductionRepository);
        var engineTax = engineWithTax(taxBracketRepository, standardDeductionRepository);

        int referenceYear = 2025;
        int birthYear = 1955; // age 70 -- below RMD start age (73), isolates the A4 fix
        LocalDate retirementDate = LocalDate.of(2020, 1, 1);

        // Pension exactly equals spending -> grossSurplus = 0, so ANY positive tax on the pension
        // exceeds the (zero) surplus -- the "tax > surplus" case from audit A4. Traditional-only
        // pool (no taxable, no roth) isolates the fix: the tax must come entirely out of
        // `traditional`, which was untouched pre-fix (the tax simply vanished).
        List<ProjectionAccountInput> accounts = List.of(
                acct("300000.0000", "0", "0.0000", "traditional"));
        var spending = new SpendingProfileInput(bd("30000"), bd("15000"), null); // 45,000 total
        var pension = incomeSource("Pension", "45000", 60, null, "0");

        // fee_rate pinned to 0 (audit B1) -- otherwise the default 0.25% drag would break the
        // "flat at 0% return" assumption baked into this test's balance-identity arithmetic below.
        var input = createInput(
                retirementDate, 95, BigDecimal.ZERO,
                """
                {"birth_year": %d, "filing_status": "single", "fee_rate": 0}
                """.formatted(birthYear),
                accounts, spending, referenceYear, List.of(pension));

        var year1 = engineTax.run(input).yearlyData().getFirst();

        assertThat(year1.age()).isEqualTo(70);
        assertThat(year1.withdrawals()).isEqualByComparingTo(BigDecimal.ZERO); // income covers spending
        assertThat(year1.surplusReinvested()).isNull(); // nothing left over to reinvest

        // Same $45,000-pension-vs-$45,000-spending BASE tax figure pinned by
        // run_incomeExactlyEqualsSpending_taxStillComputed in DeterministicProjectionEngineSpendingPlanTest.
        // Audit C2: with no taxable pool (traditional-only fixture), that $3,361.50 base bill must
        // itself drain from traditional and gets grossed up -- same warm-started fixed point
        // (base=45,000, taxableAvail=0, all-12%-bracket) as that sibling test: 3,361.50/0.88 =
        // 3,819.8864.
        var refCalc = referenceFederalCalc();
        BigDecimal expectedTax = refCalc.computeTax(bd("45000"), referenceYear, FilingStatus.SINGLE);
        assertThat(expectedTax).isEqualByComparingTo(bd("3361.5000"));
        BigDecimal expectedGrossedTax = bd("3819.8864");
        assertThat(year1.taxLiability()).isEqualByComparingTo(expectedGrossedTax);

        // A4 + C2: the (grossed-up) tax must actually leave the pool -- traditional (the only pool)
        // is debited exactly, not left untouched the way it was pre-A4, and not just the pre-C2 base
        // amount either.
        assertThat(year1.traditionalBalance()).isEqualByComparingTo(bd("300000.0000").subtract(expectedGrossedTax));

        // Balance identity: start + contrib + growth - withdrawals - taxLiability == end.
        BigDecimal expectedEnd = year1.startBalance().add(year1.contributions()).add(year1.growth())
                .subtract(year1.withdrawals()).subtract(year1.taxLiability());
        assertThat(year1.endBalance()).isEqualByComparingTo(expectedEnd);
    }

    @Test
    void run_guardrailSpendingPlanWithLiveIncomeMismatch_chargesExactBaseIncomeTaxNoGapNoOverlap() {
        stubSingle2025(taxBracketRepository, standardDeductionRepository);
        var engineTax = engineWithTax(taxBracketRepository, standardDeductionRepository);

        int referenceYear = 2025;
        int birthYear = 1955; // age 70 -- below RMD start age, isolates the base-income-tax gap
        LocalDate retirementDate = LocalDate.of(2020, 1, 1);

        // T2 gap: GuardrailSpendingInput#resolveYear ignores live income, so its precomputed
        // portfolioWithdrawal (here 0 -- the MC optimizer assumed the pension alone would cover
        // spending) can diverge from what the ENGINE's own live income actually covers. Here the
        // pension ($60,000) does NOT cover the guardrail's own recommended spending ($100,000), so
        // this is a genuine deficit year (grossSurplus < 0) even though portfolioWithdrawal == 0 --
        // exactly the scenario the old totalNeed<=0 ("noSpendDraw") proxy got backwards: it would
        // have incorrectly netted the base tax away as "already charged", charging $0 on the
        // pension (mirroring the audit's MC "$60k-pension retiree ... pays $0 tax" bug).
        var guardrailYear = new GuardrailYearlySpending(referenceYear, 70, bd("100000"),
                bd("80000"), bd("120000"), bd("70000"), bd("30000"), bd("60000"),
                BigDecimal.ZERO, "Test");
        var guardrailInput = new GuardrailSpendingInput(List.of(guardrailYear));

        List<ProjectionAccountInput> accounts = List.of(
                acct("500000.0000", "0", "0.0000", "traditional"),
                acct("100000.0000", "0", "0.0000", "roth"));
        var pension = incomeSource("Pension", "60000", 60, null, "0");

        // fee_rate pinned to 0 (audit B1) -- otherwise the default 0.25% drag would break the
        // "flat at 0% return" assumption baked into the exact traditional/roth balance assertions
        // below.
        var input = new ProjectionInput(UUID.randomUUID(), "Guardrail T2 Gap Test",
                retirementDate, 95, BigDecimal.ZERO,
                """
                {"birth_year": %d, "filing_status": "single", "fee_rate": 0}
                """.formatted(birthYear),
                accounts, null, referenceYear, List.of(pension), guardrailInput);

        var year1 = engineTax.run(input).yearlyData().getFirst();

        assertThat(year1.age()).isEqualTo(70);
        assertThat(year1.withdrawals()).isEqualByComparingTo(BigDecimal.ZERO); // guardrail said 0

        // Independent oracle: base tax owed on the $60,000 pension alone -- no RMD at 70, no spend
        // draw, no conversion, so the pension is the ONLY taxable income this year (pre-C2 figure).
        BigDecimal expectedTax = referenceFederalCalc().computeTax(bd("60000"), referenceYear, FilingStatus.SINGLE);
        assertThat(expectedTax).isGreaterThan(BigDecimal.ZERO); // sanity: the oracle itself is nonzero

        // Audit C2: no taxable pool in this fixture, so the base $5,161.50 bill itself drains from
        // traditional and grosses up -- warm-started fixed point (T10 review; base=60,000,
        // taxableAvail=0), crossing the 12%/22% bracket boundary inside the gross-up range (so the
        // chord jump under-shoots and the polish loop finishes the job), converges to 6,171.6068,
        // within $0.19 of the true fixed point 6,171.7949 -- independently reproduced against the
        // SAME single-2025 brackets/deduction, matching the engine's own output to the cent.
        BigDecimal expectedGrossedTax = bd("6171.6068");
        assertThat(year1.taxLiability()).isEqualByComparingTo(expectedGrossedTax);

        // And it must actually leave the pools (no vanishing, no gap): taxable-first cascade with
        // no taxable pool here means traditional funds it (base + gross-up) in full.
        assertThat(year1.traditionalBalance()).isEqualByComparingTo(bd("500000.0000").subtract(expectedGrossedTax));
        assertThat(year1.rothBalance()).isEqualByComparingTo(bd("100000.0000")); // untouched
    }

    @Test
    void run_selfEmploymentDeficitYear_seTaxFundedFromPoolsNotJustReported() {
        stubSingle2025(taxBracketRepository, standardDeductionRepository);
        var engineTax = engineWithTax(taxBracketRepository, standardDeductionRepository);

        int referenceYear = 2025;
        int birthYear = 1955; // age 70 -- below RMD start age, isolates the SE-tax fix
        LocalDate retirementDate = LocalDate.of(2020, 1, 1);

        // $50,000 self-employment income against $80,000 spending is a genuine deficit
        // (grossSurplus = 50,000 - 80,000 = -30,000 < 0): the spending-plan surplus branch never
        // fires, so pre-fix the SE tax was added to the reported taxLiability with ZERO pool
        // effect (audit A4's "same pattern" bug). Traditional-only pool isolates the fix.
        List<ProjectionAccountInput> accounts = List.of(
                acct("500000.0000", "0", "0.0000", "traditional"));
        var spending = new SpendingProfileInput(bd("80000"), bd("0"), null);
        var consulting = selfEmploymentSource("Consulting", "50000", 60, null);

        // fee_rate pinned to 0 (audit B1) -- otherwise the default 0.25% drag would break the
        // "flat at 0% return" assumption baked into this test's balance-identity arithmetic below.
        var input = createInput(
                retirementDate, 95, BigDecimal.ZERO,
                """
                {"birth_year": %d, "filing_status": "single", "fee_rate": 0}
                """.formatted(birthYear),
                accounts, spending, referenceYear, List.of(consulting));

        var year1 = engineTax.run(input).yearlyData().getFirst();

        assertThat(year1.age()).isEqualTo(70);
        // Independent SE-tax oracle (same no-arg calculator the engine wires internally).
        var seCalc = new SelfEmploymentTaxCalculator();
        BigDecimal expectedSeTax = seCalc.computeSETax(bd("50000"), referenceYear);
        assertThat(year1.selfEmploymentTax()).isEqualByComparingTo(expectedSeTax);

        // Ordinary tax on (spend draw $30,000 [80,000 spend - 50,000 SE cash] + SE taxable income
        // net of the 50%-deductible SE tax): the SE deduction reduces the ordinary base, so derive
        // it via the same SelfEmploymentTaxCalculator the engine uses rather than hand-rounding.
        // This is the PRE-C2 base bill (15,501.6498, still the correct A4 "SE tax must be funded"
        // figure on its own).
        BigDecimal seDeduction = seCalc.deductibleAmount(expectedSeTax);
        BigDecimal ordinaryTaxableIncome = bd("30000").add(bd("50000").subtract(seDeduction));
        BigDecimal expectedOrdinaryTax = referenceFederalCalc()
                .computeTax(ordinaryTaxableIncome, referenceYear, FilingStatus.SINGLE);
        BigDecimal expectedTotalTax = expectedOrdinaryTax.add(expectedSeTax);
        assertThat(expectedTotalTax).isEqualByComparingTo(bd("15501.6498"));

        // Audit C2: no taxable pool in this fixture, so that base bill itself drains from
        // traditional (on top of the $30,000 spend draw) and grosses up -- warm-started fixed point
        // (T10 review; base=ordinaryTaxableIncome, the SE tax as a constant, non-re-stacked addition,
        // whole range inside the 22% bracket so the closed-form jump is exact) converges to
        // 19,873.9100 -- independently reproduced against the SAME SelfEmploymentTaxCalculator +
        // single-2025 brackets/deduction, matching the engine's own output to the cent.
        BigDecimal expectedGrossedTotalTax = bd("19873.9100");
        assertThat(year1.taxLiability()).isEqualByComparingTo(expectedGrossedTotalTax);

        // A4 + C2: SE tax (and its gross-up) must actually leave the pool -- traditional funds the
        // $30,000 spend draw AND the full (grossed-up ordinary + SE) tax, not just the ordinary
        // portion the way it did pre-A4, and not just the pre-C2 base amount either.
        assertThat(year1.traditionalBalance())
                .isEqualByComparingTo(bd("500000.0000").subtract(bd("30000")).subtract(expectedGrossedTotalTax));

        // Balance identity: start + contrib + growth - withdrawals - taxLiability == end.
        BigDecimal expectedEnd = year1.startBalance().add(year1.contributions()).add(year1.growth())
                .subtract(year1.withdrawals()).subtract(year1.taxLiability());
        assertThat(year1.endBalance()).isEqualByComparingTo(expectedEnd);
    }
}
