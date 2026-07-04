package com.wealthview.projection;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.wealthview.core.projection.dto.GuardrailSpendingInput;
import com.wealthview.core.projection.dto.GuardrailYearlySpending;
import com.wealthview.core.projection.dto.IncomeSourceType;
import com.wealthview.core.projection.dto.ProjectionIncomeSourceInput;
import com.wealthview.core.projection.dto.SpendingProfileInput;

import static com.wealthview.core.testutil.TaxBracketFixtures.bd;
import static com.wealthview.core.testutil.TaxBracketFixtures.stubSingle2025;
import static com.wealthview.projection.testutil.ProjectionTestFixtures.acct;
import static com.wealthview.projection.testutil.ProjectionTestFixtures.createGuardrailInput;
import static com.wealthview.projection.testutil.ProjectionTestFixtures.createInput;
import static com.wealthview.projection.testutil.ProjectionTestFixtures.engineWithTax;
import static com.wealthview.projection.testutil.ProjectionTestFixtures.incomeSource;
import static com.wealthview.projection.testutil.ProjectionTestFixtures.property;
import static com.wealthview.projection.testutil.ProjectionTestFixtures.socialSecuritySource;
import static org.assertj.core.api.Assertions.assertThat;

class DeterministicProjectionEngineRothConversionTest extends DeterministicProjectionEngineTestSupport {

    @Test
    void run_rothConversion_movesFromTraditionalToRoth() {
        stubSingle2025(taxBracketRepository, standardDeductionRepository);
        var engineTax = engineWithTax(taxBracketRepository, standardDeductionRepository);

        var input = createInput(
                LocalDate.now().plusYears(30), 90, BigDecimal.ZERO,
                """
                {"birth_year": %d, "filing_status": "single", "annual_roth_conversion": 50000}
                """.formatted(LocalDate.now().getYear() - 35),
                List.of(
                        acct("500000.0000", "0", "0.0700", "traditional"),
                        acct("100000.0000", "0", "0.0700", "roth")));

        var result = engineTax.run(input);

        var year1 = result.yearlyData().getFirst();
        assertThat(year1.rothConversionAmount()).isNotNull();
        assertThat(year1.rothConversionAmount()).isEqualByComparingTo(bd("50000"));
        assertThat(year1.taxLiability()).isNotNull();
        assertThat(year1.taxLiability()).isGreaterThan(BigDecimal.ZERO);
    }

    @Test
    void run_rothConversionExceedsTraditionalBalance_convertsOnlyAvailable() {
        stubSingle2025(taxBracketRepository, standardDeductionRepository);
        var engineTax = engineWithTax(taxBracketRepository, standardDeductionRepository);

        var input = createInput(
                LocalDate.now().plusYears(30), 90, BigDecimal.ZERO,
                """
                {"birth_year": %d, "filing_status": "single", "annual_roth_conversion": 500000}
                """.formatted(LocalDate.now().getYear() - 35),
                List.of(
                        acct("30000.0000", "0", "0.0700", "traditional"),
                        acct("100000.0000", "0", "0.0700", "roth")));

        var result = engineTax.run(input);

        var year1 = result.yearlyData().getFirst();
        assertThat(year1.rothConversionAmount()).isLessThanOrEqualTo(bd("32100.0001"));
    }

    @Test
    void run_fillBracketStrategy_convertsToFillBracket() {
        stubSingle2025(taxBracketRepository, standardDeductionRepository);
        var engineTax = engineWithTax(taxBracketRepository, standardDeductionRepository);

        var input = createInput(
                LocalDate.now().plusYears(30), 90, BigDecimal.ZERO,
                """
                {"birth_year": %d, "filing_status": "single", "roth_conversion_strategy": "fill_bracket", "target_bracket_rate": 0.12}
                """.formatted(LocalDate.now().getYear() - 35),
                List.of(
                        acct("500000.0000", "0", "0.0700", "traditional"),
                        acct("100000.0000", "0", "0.0700", "roth")));

        var result = engineTax.run(input);

        var year1 = result.yearlyData().getFirst();
        assertThat(year1.rothConversionAmount()).isNotNull();
        // 12% bracket ceiling $48,475 + standard deduction $15,000 = $63,475
        assertThat(year1.rothConversionAmount()).isEqualByComparingTo(bd("63475"));
    }

    @Test
    void run_fillBracketStrategy_withOtherIncome_reducesConversion() {
        stubSingle2025(taxBracketRepository, standardDeductionRepository);
        var engineTax = engineWithTax(taxBracketRepository, standardDeductionRepository);

        var input = createInput(
                LocalDate.now().plusYears(30), 90, BigDecimal.ZERO,
                """
                {"birth_year": %d, "filing_status": "single", "other_income": 30000, "roth_conversion_strategy": "fill_bracket", "target_bracket_rate": 0.12}
                """.formatted(LocalDate.now().getYear() - 35),
                List.of(
                        acct("500000.0000", "0", "0.0700", "traditional"),
                        acct("100000.0000", "0", "0.0700", "roth")));

        var result = engineTax.run(input);

        var year1 = result.yearlyData().getFirst();
        // ceiling=$63,475, other_income=$30K, conversion=$33,475
        assertThat(year1.rothConversionAmount()).isEqualByComparingTo(bd("33475"));
    }

    // === Income sources affect pools/Roth/tax (Step 3) ===

    @Test
    void run_fillBracket_incomeSourceReducesBracketSpace() {
        stubSingle2025(taxBracketRepository, standardDeductionRepository);
        var engineTax = engineWithTax(taxBracketRepository, standardDeductionRepository);

        var input = createInput(
                LocalDate.now().minusYears(1), 75, BigDecimal.ZERO,
                """
                {"birth_year": %d, "filing_status": "single", "roth_conversion_strategy": "fill_bracket", "target_bracket_rate": 0.12}
                """.formatted(LocalDate.now().getYear() - 66),
                List.of(
                        acct("500000.0000", "0", "0.0500", "traditional"),
                        acct("100000.0000", "0", "0.0500", "roth")),
                new SpendingProfileInput(bd("20000"), bd("10000"), null),
                List.of(incomeSource("Social Security", "30000", 60, null, "0")));

        var result = engineTax.run(input);

        var year1 = result.yearlyData().getFirst();
        assertThat(year1.rothConversionAmount()).isEqualByComparingTo(bd("33475"));
    }

    @Test
    void run_fillBracket_incomeSourceAndOtherIncome_bothReduceSpace() {
        stubSingle2025(taxBracketRepository, standardDeductionRepository);
        var engineTax = engineWithTax(taxBracketRepository, standardDeductionRepository);

        var input = createInput(
                LocalDate.now().minusYears(1), 75, BigDecimal.ZERO,
                """
                {"birth_year": %d, "filing_status": "single", "other_income": 10000, "roth_conversion_strategy": "fill_bracket", "target_bracket_rate": 0.12}
                """.formatted(LocalDate.now().getYear() - 66),
                List.of(
                        acct("500000.0000", "0", "0.0500", "traditional"),
                        acct("100000.0000", "0", "0.0500", "roth")),
                new SpendingProfileInput(bd("20000"), bd("10000"), null),
                List.of(incomeSource("Social Security", "20000", 60, null, "0")));

        var result = engineTax.run(input);

        var year1 = result.yearlyData().getFirst();
        assertThat(year1.rothConversionAmount()).isEqualByComparingTo(bd("33475"));
    }

    // --- Roth Conversion Start Year ---

    @Test
    void run_rothConversionStartYear_skipsConversionBeforeStartYear() {
        stubSingle2025(taxBracketRepository, standardDeductionRepository);
        var engineTax = engineWithTax(taxBracketRepository, standardDeductionRepository);

        int birthYear = LocalDate.now().getYear() - 60;
        int retirementYear = LocalDate.now().getYear();
        int conversionStartYear = retirementYear + 2;

        var paramsJson = """
                {"birth_year": %d, "annual_roth_conversion": 50000, "filing_status": "single",
                 "roth_conversion_start_year": %d}
                """.formatted(birthYear, conversionStartYear);

        var input = createInput(
                LocalDate.of(retirementYear, 1, 1), 65, bd("0.03"), paramsJson,
                List.of(
                        acct("500000.0000", "0", "0.05", "traditional"),
                        acct("100000.0000", "0", "0.05", "roth")));

        var result = engineTax.run(input);

        // Years before the conversion start year should have no conversion
        var yearsBeforeStart = result.yearlyData().stream()
                .filter(y -> y.year() < conversionStartYear)
                .toList();
        assertThat(yearsBeforeStart).isNotEmpty();
        for (var year : yearsBeforeStart) {
            assertThat(year.rothConversionAmount()).isNull();
        }

        // Years at or after the conversion start year should have conversions
        var yearsAtOrAfterStart = result.yearlyData().stream()
                .filter(y -> y.year() >= conversionStartYear)
                .toList();
        assertThat(yearsAtOrAfterStart).isNotEmpty();
        assertThat(yearsAtOrAfterStart.getFirst().rothConversionAmount()).isNotNull();
    }

    @Test
    void run_rothConversionStartYear_null_convertsFromYear1() {
        stubSingle2025(taxBracketRepository, standardDeductionRepository);
        var engineTax = engineWithTax(taxBracketRepository, standardDeductionRepository);

        int birthYear = LocalDate.now().getYear() - 60;
        int retirementYear = LocalDate.now().getYear();

        var paramsJson = """
                {"birth_year": %d, "annual_roth_conversion": 50000, "filing_status": "single"}
                """.formatted(birthYear);

        var input = createInput(
                LocalDate.of(retirementYear, 1, 1), 65, bd("0.03"), paramsJson,
                List.of(
                        acct("500000.0000", "0", "0.05", "traditional"),
                        acct("100000.0000", "0", "0.05", "roth")));

        var result = engineTax.run(input);

        // Year 1 should have a conversion (existing behavior preserved)
        assertThat(result.yearlyData().getFirst().rothConversionAmount()).isNotNull();
    }

    @Test
    void run_rothConversion_exposesConversionTaxSource() {
        stubSingle2025(taxBracketRepository, standardDeductionRepository);
        var engineTax = engineWithTax(taxBracketRepository, standardDeductionRepository);

        var input = createInput(
                LocalDate.now().plusYears(30), 90, BigDecimal.ZERO,
                """
                {"birth_year": %d, "filing_status": "single", "annual_roth_conversion": 50000}
                """.formatted(LocalDate.now().getYear() - 35),
                List.of(
                        acct("500000.0000", "0", "0.0700", "traditional"),
                        acct("100000.0000", "0", "0.0700", "roth"),
                        acct("50000.0000", "0", "0.0700", "taxable")));

        var result = engineTax.run(input);

        var year1 = result.yearlyData().getFirst();
        // Tax should come from taxable first
        assertThat(year1.taxPaidFromTaxable()).isNotNull();
        assertThat(year1.taxPaidFromTaxable()).isGreaterThan(BigDecimal.ZERO);
        // Total tax paid from pools should equal tax liability
        BigDecimal totalTaxPaid = year1.taxPaidFromTaxable()
                .add(year1.taxPaidFromTraditional() != null ? year1.taxPaidFromTraditional() : BigDecimal.ZERO)
                .add(year1.taxPaidFromRoth() != null ? year1.taxPaidFromRoth() : BigDecimal.ZERO);
        assertThat(totalTaxPaid).isEqualByComparingTo(year1.taxLiability());
    }

    @Test
    void rothConversion_withDepreciation_usesReducedTaxableIncome() {
        stubSingle2025(taxBracketRepository, standardDeductionRepository);
        var engineTax = engineWithTax(taxBracketRepository, standardDeductionRepository);

        int currentYear = LocalDate.now().getYear();
        int birthYear = currentYear - 50;

        // Rental property with $90k gross and $180k depreciation year 1
        // Cash inflow = $90k - $10k opex = $80k
        // Taxable income = $90k - $10k opex - $180k dep = -$100k (deeply negative)
        var rentalWithDepreciation = new ProjectionIncomeSourceInput(
                UUID.randomUUID(), "Rental with CostSeg", IncomeSourceType.RENTAL_PROPERTY,
                bd("90000"), 0, null, bd("0"), false,
                "active_participation",
                bd("10000"), null, null, null,
                "cost_segregation",
                Map.of(currentYear, bd("180000"), currentYear + 1, bd("5000")));

        // Baseline: same cash but NO depreciation
        var rentalNoDepreciation = new ProjectionIncomeSourceInput(
                UUID.randomUUID(), "Rental no dep", IncomeSourceType.RENTAL_PROPERTY,
                bd("90000"), 0, null, bd("0"), false,
                "active_participation",
                bd("10000"), null, null, null,
                null, null);

        // fill_bracket strategy tries to fill up to a target bracket
        String paramsTemplate = """
                {"birth_year": %d, "filing_status": "single",
                 "roth_conversion_strategy": "fill_bracket", "target_bracket_rate": 0.22}
                """;

        // Run with depreciation
        var inputWithDep = createInput(
                LocalDate.of(currentYear + 15, 1, 1), 90, bd("0"),
                paramsTemplate.formatted(birthYear),
                List.of(
                        acct("500000", "0", "0.07", "traditional"),
                        acct("100000", "0", "0.07", "roth")),
                null,
                List.of(rentalWithDepreciation));
        var resultWithDep = engineTax.run(inputWithDep);

        // Run without depreciation (same cash income)
        var inputNoDep = createInput(
                LocalDate.of(currentYear + 15, 1, 1), 90, bd("0"),
                paramsTemplate.formatted(birthYear),
                List.of(
                        acct("500000", "0", "0.07", "traditional"),
                        acct("100000", "0", "0.07", "roth")),
                null,
                List.of(rentalNoDepreciation));
        var resultNoDep = engineTax.run(inputNoDep);

        // With depreciation: taxable income is much lower → more bracket space → bigger conversion
        var year1WithDep = resultWithDep.yearlyData().getFirst();
        var year1NoDep = resultNoDep.yearlyData().getFirst();

        assertThat(year1WithDep.rothConversionAmount())
                .isGreaterThan(year1NoDep.rothConversionAmount());
    }

    // === Gap #3: Social Security taxable fraction affects fill-bracket space ===

    @Test
    void run_fillBracket_withSocialSecurity_useTaxableFractionNotFullAmount() {
        stubSingle2025(taxBracketRepository, standardDeductionRepository);
        var engineTax = engineWithTax(taxBracketRepository, standardDeductionRepository);

        int retireAge = 66;
        int birthYear = LocalDate.now().getYear() - retireAge;

        // With SS typed as "other" (fully taxable $30K)
        var inputOther = createInput(
                LocalDate.now().minusYears(1), 75, BigDecimal.ZERO,
                """
                {"birth_year": %d, "filing_status": "single", "other_income": 40000,
                 "roth_conversion_strategy": "fill_bracket", "target_bracket_rate": 0.22}
                """.formatted(birthYear),
                List.of(
                        acct("1000000", "0", "0.00", "traditional"),
                        acct("100000", "0", "0.00", "roth")),
                null,
                List.of(incomeSource("Pension", "30000", retireAge, null, "0")));

        // With SS typed as "social_security" (only ~85% taxable = $25,500)
        var inputSS = createInput(
                LocalDate.now().minusYears(1), 75, BigDecimal.ZERO,
                """
                {"birth_year": %d, "filing_status": "single", "other_income": 40000,
                 "roth_conversion_strategy": "fill_bracket", "target_bracket_rate": 0.22}
                """.formatted(birthYear),
                List.of(
                        acct("1000000", "0", "0.00", "traditional"),
                        acct("100000", "0", "0.00", "roth")),
                null,
                List.of(socialSecuritySource("30000", retireAge)));

        var resultOther = engineTax.run(inputOther);
        var resultSS = engineTax.run(inputSS);

        var convOther = resultOther.yearlyData().getFirst().rothConversionAmount();
        var convSS = resultSS.yearlyData().getFirst().rothConversionAmount();

        // SS taxable fraction < full amount, so effectiveOtherIncome is lower,
        // leaving MORE room for Roth conversion
        assertThat(convSS).isGreaterThan(convOther);
    }

    // === Gap #6: Pre-retirement conversion with active income sources ===

    @Test
    void run_fillBracket_preRetirement_withIncomeSource_reducesConversion() {
        stubSingle2025(taxBracketRepository, standardDeductionRepository);
        var engineTax = engineWithTax(taxBracketRepository, standardDeductionRepository);

        int age = 50;
        int birthYear = LocalDate.now().getYear() - age;

        // Without income source
        var inputNoIncome = createInput(
                LocalDate.now().plusYears(15), 90, BigDecimal.ZERO,
                """
                {"birth_year": %d, "filing_status": "single",
                 "roth_conversion_strategy": "fill_bracket", "target_bracket_rate": 0.12}
                """.formatted(birthYear),
                List.of(
                        acct("500000", "0", "0.00", "traditional"),
                        acct("100000", "0", "0.00", "roth")));

        // With $20K pension, starting at age 49 (so age 50 is NOT the start year,
        // avoiding the 0.5 transition multiplier)
        var inputWithIncome = createInput(
                LocalDate.now().plusYears(15), 90, BigDecimal.ZERO,
                """
                {"birth_year": %d, "filing_status": "single",
                 "roth_conversion_strategy": "fill_bracket", "target_bracket_rate": 0.12}
                """.formatted(birthYear),
                List.of(
                        acct("500000", "0", "0.00", "traditional"),
                        acct("100000", "0", "0.00", "roth")),
                null,
                List.of(incomeSource("Pension", "20000", age - 1, null, "0")));

        var resultNoIncome = engineTax.run(inputNoIncome);
        var resultWithIncome = engineTax.run(inputWithIncome);

        var convNoIncome = resultNoIncome.yearlyData().getFirst().rothConversionAmount();
        var convWithIncome = resultWithIncome.yearlyData().getFirst().rothConversionAmount();

        // 12% bracket ceiling = $63,475
        // Without income: full $63,475 conversion
        assertThat(convNoIncome).isEqualByComparingTo(bd("63475"));
        // With $20K income: reduced to $63,475 - $20,000 = $43,475
        assertThat(convWithIncome).isEqualByComparingTo(bd("43475"));
    }

    // === Double-conversion fix: optimizer conversion schedule override ===

    @Test
    void run_withGuardrailConversionSchedule_usesOverrideInsteadOfFillBracket() {
        stubSingle2025(taxBracketRepository, standardDeductionRepository);
        var engineTax = engineWithTax(taxBracketRepository, standardDeductionRepository);

        int birthYear = LocalDate.now().getYear() - 66;
        int currentYear = LocalDate.now().getYear();

        // Guardrail spending with a conversion schedule: $25,000 conversion in year 1, $0 in year 2
        var guardrailYears = List.of(
                new GuardrailYearlySpending(currentYear, 66, bd("72938"), bd("60000"),
                        bd("90000"), bd("30000"), bd("42938"), BigDecimal.ZERO,
                        bd("72938"), "Early"),
                new GuardrailYearlySpending(currentYear + 1, 67, bd("73000"), bd("60000"),
                        bd("90000"), bd("30000"), bd("43000"), BigDecimal.ZERO,
                        bd("73000"), "Early"));

        // Optimizer says: convert $25,000 in year 1, nothing in year 2
        var conversionByYear = Map.of(currentYear, bd("25000"));

        var guardrailInput = new GuardrailSpendingInput(guardrailYears, conversionByYear);

        // params_json has fill_bracket at 12% — which would normally convert much more
        var input = createGuardrailInput(
                LocalDate.now().minusYears(1), 68, bd("0.0300"),
                """
                {"birth_year": %d, "filing_status": "single",
                 "roth_conversion_strategy": "fill_bracket", "target_bracket_rate": 0.12}
                """.formatted(birthYear),
                List.of(
                        acct("500000", "0", "0.0500", "traditional"),
                        acct("100000", "0", "0.0500", "roth")),
                guardrailInput);

        var result = engineTax.run(input);

        // Year 1 should use the optimizer's $25,000 conversion, NOT fill_bracket
        var year1 = result.yearlyData().getFirst();
        assertThat(year1.rothConversionAmount()).isNotNull();
        assertThat(year1.rothConversionAmount()).isEqualByComparingTo(bd("25000"));

        // Year 2: no conversion in the schedule → $0 conversion (NOT fill_bracket)
        var year2 = result.yearlyData().get(1);
        // conversionByYear doesn't have an entry for year 2, so conversion should be zero
        assertThat(year2.rothConversionAmount()).isNull();
    }

    @Test
    void run_withGuardrailNullConversionSchedule_usesFillBracketNormally() {
        stubSingle2025(taxBracketRepository, standardDeductionRepository);
        var engineTax = engineWithTax(taxBracketRepository, standardDeductionRepository);

        int birthYear = LocalDate.now().getYear() - 66;
        int currentYear = LocalDate.now().getYear();

        var guardrailYears = List.of(
                new GuardrailYearlySpending(currentYear, 66, bd("72938"), bd("60000"),
                        bd("90000"), bd("30000"), bd("42938"), BigDecimal.ZERO,
                        bd("72938"), "Early"));

        // null conversionByYear → should fall back to fill_bracket from params_json
        var guardrailInput = new GuardrailSpendingInput(guardrailYears, null);

        var input = createGuardrailInput(
                LocalDate.now().minusYears(1), 67, bd("0.0300"),
                """
                {"birth_year": %d, "filing_status": "single",
                 "roth_conversion_strategy": "fill_bracket", "target_bracket_rate": 0.12}
                """.formatted(birthYear),
                List.of(
                        acct("500000", "0", "0.0500", "traditional"),
                        acct("100000", "0", "0.0500", "roth")),
                guardrailInput);

        var result = engineTax.run(input);

        // Should use fill_bracket → conversion amount should be the bracket ceiling
        // (standard deduction $15,700 + 12% bracket ceiling ~$48,475 = ~$64,175 total income space)
        var year1 = result.yearlyData().getFirst();
        assertThat(year1.rothConversionAmount()).isNotNull();
        // fill_bracket at 12% converts more than $25,000 with $500k traditional balance
        assertThat(year1.rothConversionAmount()).isGreaterThan(bd("25000"));
    }

    @Test
    void run_withGuardrailConversionSchedule_feasibilityIsAlwaysTrue() {
        stubSingle2025(taxBracketRepository, standardDeductionRepository);
        var engineTax = engineWithTax(taxBracketRepository, standardDeductionRepository);

        int birthYear = LocalDate.now().getYear() - 66;
        int currentYear = LocalDate.now().getYear();

        // Guardrail spending with conversion schedule — optimizer already validated sustainability
        var guardrailYears = List.of(
                new GuardrailYearlySpending(currentYear, 66, bd("72938"), bd("60000"),
                        bd("90000"), bd("30000"), bd("42938"), BigDecimal.ZERO,
                        bd("72938"), "Early"),
                new GuardrailYearlySpending(currentYear + 1, 67, bd("73000"), bd("60000"),
                        bd("90000"), bd("30000"), bd("43000"), BigDecimal.ZERO,
                        bd("73000"), "Early"));

        var conversionByYear = Map.of(
                currentYear, bd("50000"),
                currentYear + 1, bd("50000"));

        var guardrailInput = new GuardrailSpendingInput(guardrailYears, conversionByYear);

        // Large conversions that would create tax pushing feasibility negative
        var input = createGuardrailInput(
                LocalDate.now().minusYears(1), 68, bd("0.0300"),
                """
                {"birth_year": %d, "filing_status": "single",
                 "roth_conversion_strategy": "fill_bracket", "target_bracket_rate": 0.22}
                """.formatted(birthYear),
                List.of(
                        acct("500000", "0", "0.0500", "traditional"),
                        acct("100000", "0", "0.0500", "roth")),
                guardrailInput);

        var result = engineTax.run(input);

        // Optimizer-validated plan should always be feasible
        assertThat(result.spendingFeasibility()).isNotNull();
        assertThat(result.spendingFeasibility().spendingFeasible()).isTrue();
        assertThat(result.spendingFeasibility().firstShortfallYear()).isNull();
    }
}
