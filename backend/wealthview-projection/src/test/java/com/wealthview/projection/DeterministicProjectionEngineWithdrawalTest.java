package com.wealthview.projection;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.wealthview.core.projection.dto.ProjectionAccountInput;

import static com.wealthview.core.testutil.TaxBracketFixtures.bd;
import static com.wealthview.core.testutil.TaxBracketFixtures.stubSingle2025;
import static com.wealthview.projection.testutil.ProjectionTestFixtures.acct;
import static com.wealthview.projection.testutil.ProjectionTestFixtures.createInput;
import static com.wealthview.projection.testutil.ProjectionTestFixtures.createRetiredInput;
import static com.wealthview.projection.testutil.ProjectionTestFixtures.engineWithTax;
import static org.assertj.core.api.Assertions.assertThat;

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
        // 1.05/1.03-1 = 0.01941748 real. Year 1: $1M * 1.01941748 = $1,019,417.48; withdrawal =
        // 4% of currentBalance = $40,776.6992.
        assertThat(year1.withdrawals()).isEqualByComparingTo(bd("40776.6992"));

        // Year 2: dynamic_percentage recomputes 4% of currentBalance every year (not held constant),
        // so it compounds down: (1,019,417.48 - 40,776.6992) * 1.01941748 * 0.04 = 39,905.7407.
        var year2 = result.yearlyData().get(1);
        assertThat(year2.withdrawals()).isEqualByComparingTo(bd("39905.7407"));
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

        // Real terms: 0% scenario inflation ⇒ 0.05 nominal override deflates to 5% real exactly.
        // Year 1: $1M grows to $1,050,000; withdrawal = 4% = $42,000 (first retirement year: raw,
        // uncapped).
        var year1 = result.yearlyData().getFirst();
        assertThat(year1.withdrawals()).isEqualByComparingTo(bd("42000.0000"));

        // Year 2: balance $1,050,000 - $42,000 = $1,008,000 grows 5% to $1,058,400; raw = 4% =
        // $42,336, within [min $40,950, max $44,100] of the prior withdrawal → raw stands.
        var year2 = result.yearlyData().get(1);
        assertThat(year2.withdrawals()).isEqualByComparingTo(bd("42336.0000"));
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
        // Real terms: 0% scenario inflation ⇒ pools grow at the nominal 5% exactly; taxable-first
        // leaves traditional untouched at 200000 * 1.05 = 210000.
        assertThat(year1.traditionalBalance()).isEqualByComparingTo(bd("210000.0000"));
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
        // Real terms: 0% scenario inflation ⇒ pools grow at the nominal 5% exactly; roth-first
        // leaves taxable/traditional untouched.
        assertThat(year1.taxableBalance()).isEqualByComparingTo(bd("315000.0000"));
        assertThat(year1.traditionalBalance()).isEqualByComparingTo(bd("210000.0000"));
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
        // Real terms: 0% scenario inflation ⇒ pools grow at the nominal 5% exactly; taxable-first
        // leaves traditional untouched at 200000 * 1.05 = 210000.
        assertThat(year1.traditionalBalance()).isEqualByComparingTo(bd("210000.0000"));
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
        // Real terms: 0% scenario inflation ⇒ -0.30 nominal override deflates to -30% real exactly.
        // Year 1: $1M * 0.70 = $700,000. Raw = * 0.04 = $28,000.
        // Year 2 raw collapses far below the floor, so the floor binds:
        // floor = $28,000 * (1 - 0.025) = $27,300 → withdrawal capped UP to the floor.
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

        // Year 1: raw = currentBalance * 0.04 = $700,000 * 0.04 = $28,000
        assertThat(year1.withdrawals()).isEqualByComparingTo(bd("28000.0000"));

        // Year 2: raw far below floor → capped at floor = 28000 * 0.975 = 27300
        assertThat(year2.withdrawals()).isEqualByComparingTo(bd("27300.0000"));

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
        var input = createRetiredInput(
                """
                {"birth_year": %d, "withdrawal_rate": 0.10, "filing_status": "single",
                 "withdrawal_order": "dynamic_sequencing", "dynamic_sequencing_bracket_rate": 0.12}
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

        var rmdInput = createInput(
                retirementDate, 95, BigDecimal.ZERO,
                """
                {"birth_year": %d, "withdrawal_rate": 0.01, "filing_status": "single", "withdrawal_order": "taxable_first"}
                """.formatted(rmdBirthYear),
                accounts, null, referenceYear, List.of());
        // Same scenario one birth-year younger: age is one below the RMD start age, so rmdAmount
        // stays zero and this run isolates what the year would have looked like without the RMD.
        var noRmdInput = createInput(
                retirementDate, 95, BigDecimal.ZERO,
                """
                {"birth_year": %d, "withdrawal_rate": 0.01, "filing_status": "single", "withdrawal_order": "taxable_first"}
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
}
