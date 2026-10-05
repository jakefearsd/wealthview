package com.wealthview.projection;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import com.wealthview.core.projection.dto.AssetAllocation;
import com.wealthview.core.projection.dto.HypotheticalAccountInput;
import com.wealthview.core.projection.dto.ProjectionAccountInput;
import com.wealthview.core.projection.dto.ProjectionInput;
import com.wealthview.core.projection.dto.ProjectionYearDto;
import com.wealthview.core.projection.dto.ScenarioParams;
import com.wealthview.core.projection.dto.SpendingProfileInput;
import com.wealthview.core.projection.dto.YearTaxPicture;
import com.wealthview.core.projection.tax.SocialSecurityTaxCalculator;
import com.wealthview.core.projection.tax.TaxCalculationStrategy;
import com.wealthview.core.projection.tax.TaxSpaceCalculator;
import com.wealthview.projection.testutil.GoldenScenarios;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Phase 1a guard (spec §1.3): for every retired year of every golden scenario, the tax-space
 * model's inputs ({@link YearTaxPicture}) must reproduce the tax the engine actually charged,
 * within $1. Listed exclusions: the early-withdrawal penalty and SE tax (both outside the
 * tax-space model; already removed from chargedOrdinaryAndStateTax by the engine). A failure here
 * is an engine/calculator disagreement to FIX, never a tolerance to widen.
 */
class TaxSpaceReconciliationTest {

    private static final BigDecimal ONE_DOLLAR = BigDecimal.ONE;
    private static final BigDecimal HALF = new BigDecimal("0.5");

    /**
     * Non-golden fixture covering what no golden does: a RETIRED-year Roth conversion, realized LTCG,
     * and a taxable pool too small to pay the year's tax so part of it is grossed up from traditional
     * (the C2 gross-up), all in the same years.
     */
    private static final String CONVERSION_GROSS_UP = "retired-conversion-ltcg-gross-up";

    static Stream<String> scenarios() {
        return Stream.concat(GoldenScenarios.NAMES.stream(), Stream.of(CONVERSION_GROSS_UP));
    }

    /**
     * Single filer, retired at 65 in 2025 with a traditional-heavy portfolio. A fixed $30,000/yr Roth
     * conversion starts in 2026 and the $50,000 spend draw sells a taxable pool (120,000, basis 40,000)
     * FIFO, so each year realizes LTCG while the conversion's tax lands on that same shrinking pool.
     * In 2027 the pool can pay only part of the bill, so the rest is grossed up from traditional while
     * LTCG is still realized and the ordinary taxable floor sits just under the 0%-LTCG ceiling, so the
     * gross-up slice moves gain across the 0%/15% line -- the case a re-stack bug would misprice.
     */
    private static ProjectionInput conversionGrossUpInput() {
        String params = """
                {"birth_year": 1960, "filing_status": "single", "withdrawal_order": "taxable_first",
                 "roth_conversion_strategy": "fixed_amount", "annual_roth_conversion": 30000,
                 "roth_conversion_start_year": 2026}
                """;
        var accounts = List.<ProjectionAccountInput>of(
                new HypotheticalAccountInput(new BigDecimal("800000"), BigDecimal.ZERO, AssetAllocation.ALL_US,
                        Optional.of(new BigDecimal("0.05")), "traditional"),
                new HypotheticalAccountInput(new BigDecimal("50000"), BigDecimal.ZERO, AssetAllocation.ALL_US,
                        Optional.of(new BigDecimal("0.05")), "roth"),
                new HypotheticalAccountInput(new BigDecimal("120000"), BigDecimal.ZERO, AssetAllocation.ALL_US,
                        Optional.of(new BigDecimal("0.05")), new BigDecimal("40000"), "taxable"));
        return new ProjectionInput(UUID.nameUUIDFromBytes(CONVERSION_GROSS_UP.getBytes()), CONVERSION_GROSS_UP,
                LocalDate.parse("2025-01-01"), 90, new BigDecimal("0.02"), params, accounts,
                new SpendingProfileInput(new BigDecimal("50000"), BigDecimal.ZERO, null), 2025, List.of(), null,
                List.of(), null);
    }

    private static ProjectionInput inputFor(String scenario) throws Exception {
        return CONVERSION_GROSS_UP.equals(scenario)
                ? conversionGrossUpInput() : GoldenScenarios.loadInput(scenario);
    }

    private record Fixture(GoldenScenarios.Calculators calcs, TaxCalculationStrategy strategy,
                           List<YearTaxPicture> pictures, int retiredYears, List<ProjectionYearDto> years) {
    }

    private static Fixture run(String scenario) throws Exception {
        var calcs = GoldenScenarios.calculators();
        var input = inputFor(scenario);
        var params = ScenarioParams.parseOrEmpty(GoldenScenarios.MAPPER, input.paramsJson());
        var strategy = new TaxStrategyFactory(calcs.federal(), null).buildTaxStrategy(params, input.household());
        var engine = new DeterministicProjectionEngine(calcs.federal(), null, calcs.capitalGains(), calcs.irmaa());
        var detail = engine.runDetailed(input);
        int retiredYears = (int) detail.result().yearlyData().stream().filter(y -> y.retired()).count();
        return new Fixture(calcs, strategy, detail.taxPictures(), retiredYears, detail.result().yearlyData());
    }

    /**
     * Gross ordinary income as the tax calculators see it: other ordinary income (ex Social Security)
     * + taxable Social Security + traditional distributions + Roth conversion + ordinary interest.
     */
    private static BigDecimal grossOrdinary(YearTaxPicture p) {
        return p.ordinaryIncomeExSocialSecurity().add(p.socialSecurityTaxable())
                .add(p.traditionalDistributions()).add(p.rothConversion()).add(p.ordinaryInterest());
    }

    @Test
    void conversionGrossUpFixture_exercisesRetiredConversionAndGrossUpInTheSameYear() throws Exception {
        var f = run(CONVERSION_GROSS_UP);

        var retired = f.years().stream().filter(ProjectionYearDto::retired).toList();
        assertThat(retired).anySatisfy(y -> assertThat(y.rothConversionAmount()).isPositive());
        assertThat(retired).anySatisfy(y -> assertThat(y.taxPaidFromTraditional()).isPositive());
        assertThat(retired).as("a year with conversion, LTCG and a traditional gross-up together")
                .anySatisfy(y -> {
                    assertThat(y.rothConversionAmount()).isPositive();
                    assertThat(y.capitalGainsTax()).isPositive();
                    assertThat(y.taxPaidFromTraditional()).isPositive();
                });
        assertThat(f.pictures()).anySatisfy(p -> assertThat(p.rothConversion()).isPositive());
    }

    @ParameterizedTest(name = "picture per retired year: {0}")
    @MethodSource("scenarios")
    void runDetailed_everyRetiredYear_hasExactlyOnePicture(String scenario) throws Exception {
        var f = run(scenario);

        assertThat(f.pictures()).hasSize(f.retiredYears());
    }

    @ParameterizedTest(name = "MAGI composition: {0}")
    @MethodSource("scenarios")
    void picture_magi_equalsGrossOrdinaryPlusLtcgWithinOneDollar(String scenario) throws Exception {
        var f = run(scenario);

        for (var p : f.pictures()) {
            assertThat(p.magi()).as("%s %d MAGI", scenario, p.year())
                    .isCloseTo(grossOrdinary(p).add(p.qualifiedDividendsAndLtcg()), within(ONE_DOLLAR));
        }
    }

    @ParameterizedTest(name = "ordinary+state reconciles: {0}")
    @MethodSource("scenarios")
    void picture_recomputedOrdinaryAndStateTax_matchesChargedWithinOneDollar(String scenario) throws Exception {
        var f = run(scenario);

        for (var p : f.pictures()) {
            var detailTax = f.strategy().computeDetailedTax(grossOrdinary(p), p.year(), p.filingStatus(),
                    p.qualifiedDividendsAndLtcg(), p.socialSecurityTaxable());

            assertThat(detailTax.totalTax()).as("%s %d ordinary+state", scenario, p.year())
                    .isCloseTo(p.chargedOrdinaryAndStateTax(), within(ONE_DOLLAR));
        }
    }

    @ParameterizedTest(name = "LTCG reconciles: {0}")
    @MethodSource("scenarios")
    void picture_recomputedLtcgTax_matchesChargedWithinOneDollar(String scenario) throws Exception {
        var f = run(scenario);

        for (var p : f.pictures()) {
            BigDecimal ltcg = p.qualifiedDividendsAndLtcg();
            BigDecimal expected = BigDecimal.ZERO;
            if (ltcg.signum() > 0) {
                BigDecimal gross = grossOrdinary(p);
                var detailTax = f.strategy().computeDetailedTax(gross, p.year(), p.filingStatus(),
                        ltcg, p.socialSecurityTaxable());
                BigDecimal deduction = detailTax.usedItemized()
                        ? detailTax.itemizedDeductions()
                        : f.strategy().standardDeduction(p.year(), p.filingStatus()).orElseThrow();
                BigDecimal ordinaryForLtcg = gross.subtract(deduction).max(BigDecimal.ZERO);
                expected = f.calcs().capitalGains().computeLtcgTax(ordinaryForLtcg, ltcg, p.year(),
                        p.filingStatus(), p.yearsFromBase(), p.inflationRate(), gross.add(ltcg),
                        p.netRentalIncome());
            }

            assertThat(expected).as("%s %d LTCG", scenario, p.year())
                    .isCloseTo(p.chargedLtcgTax(), within(ONE_DOLLAR));
        }
    }

    @ParameterizedTest(name = "SS taxable reconciles: {0}")
    @MethodSource("scenarios")
    void picture_socialSecurityTaxable_matchesIrsProvisionalIncomeWithinOneDollar(String scenario) throws Exception {
        var f = run(scenario);
        var taxSpaceCalculator = new TaxSpaceCalculator(
                f.calcs().federal(), f.calcs().capitalGains(), f.calcs().irmaa());

        for (var p : f.pictures()) {
            if (p.socialSecurityBenefit().signum() <= 0) {
                continue;
            }
            BigDecimal provisionalOther = p.ordinaryIncomeExSocialSecurity().add(p.traditionalDistributions())
                    .add(p.rothConversion()).add(p.ordinaryInterest()).add(p.qualifiedDividendsAndLtcg());

            BigDecimal expectedSsTaxable = new SocialSecurityTaxCalculator().computeTaxableAmount(
                    p.socialSecurityBenefit(), provisionalOther, p.filingStatus().value(),
                    p.yearsFromBase(), p.inflationRate());
            var space = taxSpaceCalculator.compute(p, f.strategy());

            assertThat(expectedSsTaxable).as("%s %d SS taxable", scenario, p.year())
                    .isCloseTo(p.socialSecurityTaxable(), within(ONE_DOLLAR));
            assertThat(space.provisionalIncome()).as("%s %d provisional income", scenario, p.year())
                    .isCloseTo(provisionalOther.add(p.socialSecurityBenefit().multiply(HALF)), within(ONE_DOLLAR));
        }
    }
}
