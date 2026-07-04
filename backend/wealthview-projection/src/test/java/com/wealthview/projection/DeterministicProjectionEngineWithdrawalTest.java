package com.wealthview.projection;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

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
        assertThat(year1.withdrawals()).isEqualByComparingTo(bd("42000.0000"));

        var year2 = result.yearlyData().get(1);
        assertThat(year2.withdrawals()).isEqualByComparingTo(bd("42336.0000"));
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

        var year1 = result.yearlyData().getFirst();
        assertThat(year1.withdrawals()).isEqualByComparingTo(bd("42000.0000"));

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
            assertThat(year2.withdrawals()).isEqualByComparingTo(bd("41200.0000"));
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
        // Vanguard uses currentBalance (after growth), not startOfYearBalance
        // Year 1: $1M * 0.70 (growth) = $700K. Raw = $700K * 0.04 = $28,000
        // Year 2: ($700K - $28K) * 0.70 = $470,400. Raw = $470,400 * 0.04 = $18,816
        // Floor: $28,000 * (1 - 0.025) = $27,300
        // Raw ($18,816) < floor ($27,300) → capped UP to $27,300
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

        // Year 1: raw = currentBalance * 0.04 = $700K * 0.04 = $28,000
        assertThat(year1.withdrawals()).isEqualByComparingTo(bd("28000"));

        // Year 2: raw = $18,816 < floor $27,300 → capped at floor
        assertThat(year2.withdrawals()).isEqualByComparingTo(bd("27300"));

        // Floor prevented a 33% drop — withdrawal only dropped 2.5%
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

        // All traditional should be drawn ($30K < bracket space)
        assertThat(year1.withdrawalFromTraditional()).isNotNull();
        assertThat(year1.withdrawalFromTraditional()).isEqualByComparingTo(bd("30000"));

        // Taxable covers the remainder ($33K - $30K = $3K)
        assertThat(year1.withdrawalFromTaxable()).isNotNull();
        assertThat(year1.withdrawalFromTaxable()).isEqualByComparingTo(bd("3000"));

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
}
