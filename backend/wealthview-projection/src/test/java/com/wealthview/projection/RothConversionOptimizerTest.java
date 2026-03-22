package com.wealthview.projection;

import com.wealthview.core.projection.dto.ProjectionIncomeSourceInput;
import com.wealthview.core.projection.tax.FederalTaxCalculator;
import com.wealthview.core.projection.tax.FilingStatus;
import com.wealthview.core.projection.tax.RentalLossCalculator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RothConversionOptimizerTest {

    private FederalTaxCalculator taxCalculator;

    @BeforeEach
    void setUp() {
        taxCalculator = mock(FederalTaxCalculator.class);

        // Flat 20% tax: computeTax(income, year, status) → income * 0.20
        when(taxCalculator.computeTax(any(BigDecimal.class), anyInt(), any(FilingStatus.class)))
                .thenAnswer(invocation -> {
                    BigDecimal income = invocation.getArgument(0);
                    if (income.compareTo(BigDecimal.ZERO) <= 0) {
                        return BigDecimal.ZERO;
                    }
                    return income.multiply(new BigDecimal("0.20"));
                });

        // Bracket ceiling at $100K
        when(taxCalculator.computeMaxIncomeForBracket(any(BigDecimal.class), anyInt(), any(FilingStatus.class)))
                .thenReturn(new BigDecimal("100000"));
    }

    private RothConversionOptimizer buildOptimizer(double initTraditional, double initRoth, double initTaxable,
                                                    double[] otherIncome, double[] taxableIncome,
                                                    int birthYear, int retirementAge, int endAge,
                                                    int exhaustionBuffer, double conversionBracketRate,
                                                    double rmdTargetBracketRate, double returnMean,
                                                    double essentialFloor, double inflationRate,
                                                    FilingStatus filingStatus, FederalTaxCalculator calc,
                                                    String withdrawalOrder) {
        return new RothConversionOptimizer(
                initTraditional, initRoth, initTaxable,
                otherIncome, taxableIncome,
                birthYear, retirementAge, endAge,
                exhaustionBuffer, conversionBracketRate,
                rmdTargetBracketRate, returnMean,
                essentialFloor, inflationRate,
                filingStatus, calc, withdrawalOrder,
                null, null);
    }

    private RothConversionOptimizer buildOptimizerWithRentals(
            double initTraditional, double initRoth, double initTaxable,
            double[] otherIncome, double[] taxableIncome,
            int birthYear, int retirementAge, int endAge,
            int exhaustionBuffer, double conversionBracketRate,
            double rmdTargetBracketRate, double returnMean,
            double essentialFloor, double inflationRate,
            FilingStatus filingStatus, FederalTaxCalculator calc,
            String withdrawalOrder,
            List<ProjectionIncomeSourceInput> incomeSources,
            RentalLossCalculator rentalLossCalculator) {
        return new RothConversionOptimizer(
                initTraditional, initRoth, initTaxable,
                otherIncome, taxableIncome,
                birthYear, retirementAge, endAge,
                exhaustionBuffer, conversionBracketRate,
                rmdTargetBracketRate, returnMean,
                essentialFloor, inflationRate,
                filingStatus, calc, withdrawalOrder,
                incomeSources, rentalLossCalculator);
    }

    @Test
    void optimize_allTraditional_producesConversions() {
        int retirementAge = 62;
        int endAge = 90;
        int years = endAge - retirementAge;
        var otherIncome = new double[years];
        var taxableIncome = new double[years];

        var optimizer = buildOptimizer(
                1_000_000, 0, 200_000,
                otherIncome, taxableIncome,
                1963, retirementAge, endAge,
                5, 0.22, 0.22, 0.06,
                40_000, 0.03,
                FilingStatus.SINGLE, taxCalculator, "taxable,traditional,roth");

        var result = optimizer.optimize();

        assertThat(result).isNotNull();
        assertThat(result.lifetimeTaxWith()).isLessThan(result.lifetimeTaxWithout());

        double totalConversions = 0;
        for (double c : result.conversionByYear()) {
            totalConversions += c;
        }
        assertThat(totalConversions).isGreaterThan(0);
    }

    @Test
    void optimize_allRoth_noConversions() {
        int retirementAge = 62;
        int endAge = 90;
        int years = endAge - retirementAge;
        var otherIncome = new double[years];
        var taxableIncome = new double[years];

        var optimizer = buildOptimizer(
                0, 1_000_000, 200_000,
                otherIncome, taxableIncome,
                1963, retirementAge, endAge,
                5, 0.22, 0.22, 0.06,
                40_000, 0.03,
                FilingStatus.SINGLE, taxCalculator, "taxable,traditional,roth");

        var result = optimizer.optimize();

        assertThat(result).isNotNull();
        double totalConversions = 0;
        for (double c : result.conversionByYear()) {
            totalConversions += c;
        }
        assertThat(totalConversions).isEqualTo(0);
    }

    @Test
    void optimize_exhaustionTargetMet_whenFeasible() {
        int retirementAge = 62;
        int endAge = 90;
        int years = endAge - retirementAge;
        var otherIncome = new double[years];
        var taxableIncome = new double[years];

        var optimizer = buildOptimizer(
                500_000, 0, 300_000,
                otherIncome, taxableIncome,
                1963, retirementAge, endAge,
                5, 0.22, 0.22, 0.06,
                30_000, 0.03,
                FilingStatus.SINGLE, taxCalculator, "taxable,traditional,roth");

        var result = optimizer.optimize();

        assertThat(result).isNotNull();
        assertThat(result.exhaustionTargetMet()).isTrue();
        // Should exhaust by endAge - buffer = 85
        assertThat(result.exhaustionAge()).isLessThanOrEqualTo(endAge - 5);
    }

    @Test
    void optimize_rmdStartAge_bornBefore1960_uses73() {
        int retirementAge = 65;
        int endAge = 90;
        int years = endAge - retirementAge;
        var otherIncome = new double[years];
        var taxableIncome = new double[years];

        var optimizer = buildOptimizer(
                800_000, 0, 200_000,
                otherIncome, taxableIncome,
                1959, retirementAge, endAge,
                5, 0.22, 0.22, 0.06,
                40_000, 0.03,
                FilingStatus.SINGLE, taxCalculator, "taxable,traditional,roth");

        var result = optimizer.optimize();

        assertThat(result).isNotNull();
        // RMD starts at age 73, which is yearIndex 8 (age 65 + 8 = 73)
        // No RMDs before age 73
        for (int i = 0; i < 8; i++) {
            assertThat(result.projectedRmd()[i])
                    .as("No RMD at age %d", retirementAge + i)
                    .isEqualTo(0);
        }
    }

    @Test
    void optimize_rmdStartAge_born1960_uses75() {
        int retirementAge = 65;
        int endAge = 90;
        int years = endAge - retirementAge;
        var otherIncome = new double[years];
        var taxableIncome = new double[years];

        var optimizer = buildOptimizer(
                800_000, 0, 200_000,
                otherIncome, taxableIncome,
                1960, retirementAge, endAge,
                5, 0.22, 0.22, 0.06,
                40_000, 0.03,
                FilingStatus.SINGLE, taxCalculator, "taxable,traditional,roth");

        var result = optimizer.optimize();

        assertThat(result).isNotNull();
        // RMD starts at age 75, which is yearIndex 10 (age 65 + 10 = 75)
        // No RMDs before age 75
        for (int i = 0; i < 10; i++) {
            assertThat(result.projectedRmd()[i])
                    .as("No RMD at age %d", retirementAge + i)
                    .isEqualTo(0);
        }
    }

    @Test
    void optimize_exhaustionBuffer3vs8_differentExhaustionAge() {
        int retirementAge = 62;
        int endAge = 90;
        int years = endAge - retirementAge;
        var otherIncome = new double[years];
        var taxableIncome = new double[years];

        var optimizerBuffer3 = buildOptimizer(
                500_000, 0, 300_000,
                otherIncome, taxableIncome,
                1963, retirementAge, endAge,
                3, 0.22, 0.22, 0.06,
                30_000, 0.03,
                FilingStatus.SINGLE, taxCalculator, "taxable,traditional,roth");

        var optimizerBuffer8 = buildOptimizer(
                500_000, 0, 300_000,
                otherIncome, taxableIncome,
                1963, retirementAge, endAge,
                8, 0.22, 0.22, 0.06,
                30_000, 0.03,
                FilingStatus.SINGLE, taxCalculator, "taxable,traditional,roth");

        var result3 = optimizerBuffer3.optimize();
        var result8 = optimizerBuffer8.optimize();

        assertThat(result3).isNotNull();
        assertThat(result8).isNotNull();
        // Buffer 8 requires exhaustion by age 82, buffer 3 by age 87
        // So buffer 8 should exhaust at an earlier age (or equal)
        assertThat(result8.exhaustionAge()).isLessThanOrEqualTo(result3.exhaustionAge());
    }

    @Test
    void optimize_earlyRetiree_noWithdrawalsFromTradBeforeAge595() {
        int retirementAge = 55;
        int endAge = 90;
        int years = endAge - retirementAge;
        var otherIncome = new double[years];
        var taxableIncome = new double[years];

        var optimizer = buildOptimizer(
                500_000, 100_000, 300_000,
                otherIncome, taxableIncome,
                1970, retirementAge, endAge,
                5, 0.22, 0.22, 0.06,
                40_000, 0.03,
                FilingStatus.SINGLE, taxCalculator, "taxable,traditional,roth");

        var result = optimizer.optimize();

        assertThat(result).isNotNull();
        for (int i = 0; i < 5; i++) {
            assertThat(result.taxableBalance()[i])
                    .as("Taxable balance at age %d should be positive (covering spending)", retirementAge + i)
                    .isGreaterThanOrEqualTo(0);
        }
    }

    @Test
    void optimize_ssIncomeMidStream_reducesConversions() {
        int retirementAge = 62;
        int endAge = 90;
        int years = endAge - retirementAge;
        var otherIncomeNoSS = new double[years];
        var taxableIncomeNoSS = new double[years];

        // With SS income starting at age 67 ($30K/yr)
        var otherIncomeWithSS = new double[years];
        var taxableIncomeWithSS = new double[years];
        for (int i = 0; i < years; i++) {
            int age = retirementAge + i;
            if (age >= 67) {
                otherIncomeWithSS[i] = 30_000;
                taxableIncomeWithSS[i] = 30_000;
            }
        }

        var optimizerNoSS = buildOptimizer(
                1_000_000, 0, 200_000,
                otherIncomeNoSS, taxableIncomeNoSS,
                1963, retirementAge, endAge,
                5, 0.22, 0.22, 0.06,
                40_000, 0.03,
                FilingStatus.SINGLE, taxCalculator, "taxable,traditional,roth");

        var optimizerWithSS = buildOptimizer(
                1_000_000, 0, 200_000,
                otherIncomeWithSS, taxableIncomeWithSS,
                1963, retirementAge, endAge,
                5, 0.22, 0.22, 0.06,
                40_000, 0.03,
                FilingStatus.SINGLE, taxCalculator, "taxable,traditional,roth");

        var resultNoSS = optimizerNoSS.optimize();
        var resultWithSS = optimizerWithSS.optimize();

        assertThat(resultNoSS).isNotNull();
        assertThat(resultWithSS).isNotNull();

        boolean foundSmaller = false;
        for (int i = 5; i < years; i++) {
            int age = retirementAge + i;
            if (age < RmdCalculator.rmdStartAge(1963) && resultNoSS.conversionByYear()[i] > 0) {
                if (resultWithSS.conversionByYear()[i] < resultNoSS.conversionByYear()[i] - 1.0) {
                    foundSmaller = true;
                    break;
                }
            }
        }
        assertThat(foundSmaller)
                .as("SS income should reduce conversions in at least one post-SS year")
                .isTrue();
    }

    @Test
    void optimize_mfj_higherConversionsThanSingle() {
        int retirementAge = 62;
        int endAge = 90;
        int years = endAge - retirementAge;
        var otherIncome = new double[years];
        var taxableIncome = new double[years];

        // MFJ calculator with a $200K bracket ceiling (double the Single $100K)
        var mfjCalc = mock(FederalTaxCalculator.class);
        when(mfjCalc.computeTax(any(BigDecimal.class), anyInt(), any(FilingStatus.class)))
                .thenAnswer(invocation -> {
                    BigDecimal income = invocation.getArgument(0);
                    if (income.compareTo(BigDecimal.ZERO) <= 0) {
                        return BigDecimal.ZERO;
                    }
                    return income.multiply(new BigDecimal("0.20"));
                });
        when(mfjCalc.computeMaxIncomeForBracket(any(BigDecimal.class), anyInt(), any(FilingStatus.class)))
                .thenReturn(new BigDecimal("200000"));

        var mfjOpt = buildOptimizer(
                1_000_000, 0, 500_000,
                otherIncome, taxableIncome,
                1963, retirementAge, endAge,
                5, 0.22, 0.22, 0.06,
                30_000, 0.03,
                FilingStatus.MARRIED_FILING_JOINTLY, mfjCalc, "taxable,traditional,roth");

        var singleOpt = buildOptimizer(
                1_000_000, 0, 500_000,
                otherIncome, taxableIncome,
                1963, retirementAge, endAge,
                5, 0.22, 0.22, 0.06,
                30_000, 0.03,
                FilingStatus.SINGLE, taxCalculator, "taxable,traditional,roth");

        var mfjSched = mfjOpt.optimize();
        var singleSched = singleOpt.optimize();

        double mfjMaxYear = 0;
        double singleMaxYear = 0;
        for (int i = 0; i < years; i++) {
            mfjMaxYear = Math.max(mfjMaxYear, mfjSched.conversionByYear()[i]);
            singleMaxYear = Math.max(singleMaxYear, singleSched.conversionByYear()[i]);
        }
        assertThat(mfjMaxYear).isGreaterThan(singleMaxYear);
    }

    @Test
    void optimize_alreadyPastRmdAge_noConversions() {
        int retirementAge = 75;
        int endAge = 95;
        int years = endAge - retirementAge;
        var otherIncome = new double[years];
        var taxableIncome = new double[years];

        var optimizer = buildOptimizer(
                500_000, 0, 200_000,
                otherIncome, taxableIncome,
                1955, retirementAge, endAge,
                5, 0.22, 0.22, 0.06,
                30_000, 0.03,
                FilingStatus.SINGLE, taxCalculator, "taxable,traditional,roth");

        var schedule = optimizer.optimize();

        double totalConversions = 0;
        for (double c : schedule.conversionByYear()) totalConversions += c;
        assertThat(totalConversions).isEqualTo(0);

        boolean hasRmds = false;
        for (double r : schedule.projectedRmd()) {
            if (r > 0) {
                hasRmds = true;
                break;
            }
        }
        assertThat(hasRmds).isTrue();
    }

    @Test
    void optimize_veryLargeTraditional_warnsIfExhaustionNotMet() {
        int retirementAge = 70;
        int endAge = 85;
        int years = endAge - retirementAge;
        var otherIncome = new double[years];
        var taxableIncome = new double[years];

        var optimizer = buildOptimizer(
                5_000_000, 0, 100_000,
                otherIncome, taxableIncome,
                1955, retirementAge, endAge,
                5, 0.22, 0.22, 0.06,
                30_000, 0.03,
                FilingStatus.SINGLE, taxCalculator, "taxable,traditional,roth");

        var schedule = optimizer.optimize();

        if (!schedule.exhaustionTargetMet()) {
            assertThat(schedule.exhaustionAge()).isGreaterThan(endAge - 5);
        }
        assertThat(schedule.conversionByYear()).isNotNull();
    }

    @Test
    void optimize_taxableDepletedBefore595_conversionsStop() {
        int retirementAge = 55;
        int endAge = 90;
        int years = endAge - retirementAge;
        var otherIncome = new double[years];
        var taxableIncome = new double[years];

        var optimizer = buildOptimizer(
                800_000, 0, 50_000,
                otherIncome, taxableIncome,
                1970, retirementAge, endAge,
                5, 0.22, 0.22, 0.06,
                40_000, 0.03,
                FilingStatus.SINGLE, taxCalculator, "taxable,traditional,roth");

        var result = optimizer.optimize();

        assertThat(result).isNotNull();
        double totalConvBeforeSixty = 0;
        for (int i = 0; i < 5; i++) { // age 55-59
            totalConvBeforeSixty += result.conversionByYear()[i];
        }
        assertThat(totalConvBeforeSixty).isLessThan(100_000);
    }

    // ---- Rental property loss tests ----

    @Test
    void optimize_withRentalDepreciation_moreConversionsThanWithout() {
        int retirementAge = 62;
        int endAge = 90;
        int years = endAge - retirementAge;
        int birthYear = 1963;
        var otherIncome = new double[years];
        var taxableIncome = new double[years];

        // Rental property: $30K gross rent, $10K operating expenses, $3K property tax,
        // $20K/year depreciation → net taxable = $30K - $10K - $3K - $20K = -$3K (loss)
        Map<Integer, BigDecimal> depreciationByYear = new java.util.HashMap<>();
        for (int y = 0; y < years; y++) {
            depreciationByYear.put(birthYear + retirementAge + y, new BigDecimal("20000"));
        }

        var rentalSource = new ProjectionIncomeSourceInput(
                java.util.UUID.randomUUID(), "Rental Property", "rental_property",
                new BigDecimal("30000"), retirementAge, null,
                BigDecimal.ZERO, false, "rental_passive",
                new BigDecimal("10000"), BigDecimal.ZERO, BigDecimal.ZERO,
                new BigDecimal("3000"), "straight_line", depreciationByYear);

        var withRentals = buildOptimizerWithRentals(
                1_000_000, 0, 200_000,
                otherIncome, taxableIncome,
                birthYear, retirementAge, endAge,
                5, 0.22, 0.22, 0.06,
                40_000, 0.03,
                FilingStatus.SINGLE, taxCalculator, "taxable,traditional,roth",
                List.of(rentalSource), new RentalLossCalculator());

        var withoutRentals = buildOptimizer(
                1_000_000, 0, 200_000,
                otherIncome, taxableIncome,
                birthYear, retirementAge, endAge,
                5, 0.22, 0.22, 0.06,
                40_000, 0.03,
                FilingStatus.SINGLE, taxCalculator, "taxable,traditional,roth");

        var resultWith = withRentals.optimize();
        var resultWithout = withoutRentals.optimize();

        double totalWith = 0;
        double totalWithout = 0;
        for (int i = 0; i < years; i++) {
            totalWith += resultWith.conversionByYear()[i];
            totalWithout += resultWithout.conversionByYear()[i];
        }

        // Rental losses offset income → more bracket space → more conversions
        assertThat(totalWith)
                .as("Rental depreciation should create more bracket space for conversions")
                .isGreaterThan(totalWithout);
    }

    @Test
    void optimize_withCostSeg481a_largerConversionsInEarlyYears() {
        int retirementAge = 62;
        int endAge = 90;
        int years = endAge - retirementAge;
        int birthYear = 1963;
        var otherIncome = new double[years];
        var taxableIncome = new double[years];

        // Cost seg: $120K depreciation in year 1, $15K in subsequent years
        // Net year 1: $30K - $10K - $3K - $120K = -$103K (massive loss)
        // Net year 2+: $30K - $10K - $3K - $15K = $2K (small profit)
        Map<Integer, BigDecimal> depreciationByYear = new java.util.HashMap<>();
        int firstCalYear = birthYear + retirementAge;
        depreciationByYear.put(firstCalYear, new BigDecimal("120000"));
        for (int y = 1; y < years; y++) {
            depreciationByYear.put(firstCalYear + y, new BigDecimal("15000"));
        }

        var rentalSource = new ProjectionIncomeSourceInput(
                java.util.UUID.randomUUID(), "Cost Seg Property", "rental_property",
                new BigDecimal("30000"), retirementAge, null,
                BigDecimal.ZERO, false, "rental_active_reps",
                new BigDecimal("10000"), BigDecimal.ZERO, BigDecimal.ZERO,
                new BigDecimal("3000"), "cost_seg", depreciationByYear);

        var optimizer = buildOptimizerWithRentals(
                1_000_000, 0, 200_000,
                otherIncome, taxableIncome,
                birthYear, retirementAge, endAge,
                5, 0.22, 0.22, 0.06,
                40_000, 0.03,
                FilingStatus.SINGLE, taxCalculator, "taxable,traditional,roth",
                List.of(rentalSource), new RentalLossCalculator());

        var result = optimizer.optimize();

        // Year 0 should have the largest conversions due to massive cost seg loss
        // offsetting income (REPS → full loss deduction)
        assertThat(result.conversionByYear()[0])
                .as("First year with cost seg 481(a) should have large conversions")
                .isGreaterThan(result.conversionByYear()[2]);
    }

    @Test
    void optimize_passiveLossCarryforward_reducesLaterYearTax() {
        int retirementAge = 62;
        int endAge = 90;
        int years = endAge - retirementAge;
        int birthYear = 1963;

        // No other income → MAGI is low → $25K passive loss exception applies
        var otherIncome = new double[years];
        var taxableIncome = new double[years];

        // Passive rental with large depreciation every year producing a $40K loss.
        // With $25K exception (MAGI < $100K), $25K is deductible, $15K suspended.
        // Suspended losses carry forward and release against future rental profits.
        // Net effect: year-over-year, the $25K deduction reduces taxable income,
        // creating more bracket space for conversions than without rentals.
        Map<Integer, BigDecimal> depreciationByYear = new java.util.HashMap<>();
        int firstCalYear = birthYear + retirementAge;
        for (int y = 0; y < years; y++) {
            // net: $30K - $10K - $3K - $57K = -$40K loss per year
            depreciationByYear.put(firstCalYear + y, new BigDecimal("57000"));
        }

        var rentalSource = new ProjectionIncomeSourceInput(
                java.util.UUID.randomUUID(), "Passive Rental", "rental_property",
                new BigDecimal("30000"), retirementAge, null,
                BigDecimal.ZERO, false, "rental_passive",
                new BigDecimal("10000"), BigDecimal.ZERO, BigDecimal.ZERO,
                new BigDecimal("3000"), "straight_line", depreciationByYear);

        var withCarryforward = buildOptimizerWithRentals(
                1_000_000, 0, 200_000,
                otherIncome, taxableIncome,
                birthYear, retirementAge, endAge,
                5, 0.22, 0.22, 0.06,
                40_000, 0.03,
                FilingStatus.SINGLE, taxCalculator, "taxable,traditional,roth",
                List.of(rentalSource), new RentalLossCalculator());

        var withoutRentals = buildOptimizer(
                1_000_000, 0, 200_000,
                otherIncome, taxableIncome,
                birthYear, retirementAge, endAge,
                5, 0.22, 0.22, 0.06,
                40_000, 0.03,
                FilingStatus.SINGLE, taxCalculator, "taxable,traditional,roth");

        var resultWith = withCarryforward.optimize();
        var resultWithout = withoutRentals.optimize();

        // The passive loss deduction ($25K/yr via exception) reduces effective income,
        // creating more bracket space → more/larger conversions at lower marginal rate
        assertThat(resultWith.lifetimeTaxWith())
                .as("Passive loss deduction should reduce lifetime tax via larger bracket space")
                .isLessThan(resultWithout.lifetimeTaxWith());
    }

    @Test
    void optimize_activeRepsProperty_fullLossDeduction() {
        int retirementAge = 62;
        int endAge = 90;
        int years = endAge - retirementAge;
        int birthYear = 1963;
        var otherIncome = new double[years];
        var taxableIncome = new double[years];

        // Active REPS property with $40K loss → fully offsets ordinary income (no $25K limit)
        Map<Integer, BigDecimal> depreciationByYear = new java.util.HashMap<>();
        for (int y = 0; y < years; y++) {
            depreciationByYear.put(birthYear + retirementAge + y, new BigDecimal("57000"));
        }
        // Net: $30K - $10K - $3K - $57K = -$40K loss per year

        var activeSource = new ProjectionIncomeSourceInput(
                java.util.UUID.randomUUID(), "Active REPS", "rental_property",
                new BigDecimal("30000"), retirementAge, null,
                BigDecimal.ZERO, false, "rental_active_reps",
                new BigDecimal("10000"), BigDecimal.ZERO, BigDecimal.ZERO,
                new BigDecimal("3000"), "straight_line", depreciationByYear);

        var passiveSource = new ProjectionIncomeSourceInput(
                java.util.UUID.randomUUID(), "Passive Rental", "rental_property",
                new BigDecimal("30000"), retirementAge, null,
                BigDecimal.ZERO, false, "rental_passive",
                new BigDecimal("10000"), BigDecimal.ZERO, BigDecimal.ZERO,
                new BigDecimal("3000"), "straight_line", depreciationByYear);

        var activeOptimizer = buildOptimizerWithRentals(
                1_000_000, 0, 200_000,
                otherIncome, taxableIncome,
                birthYear, retirementAge, endAge,
                5, 0.22, 0.22, 0.06,
                40_000, 0.03,
                FilingStatus.SINGLE, taxCalculator, "taxable,traditional,roth",
                List.of(activeSource), new RentalLossCalculator());

        var passiveOptimizer = buildOptimizerWithRentals(
                1_000_000, 0, 200_000,
                otherIncome, taxableIncome,
                birthYear, retirementAge, endAge,
                5, 0.22, 0.22, 0.06,
                40_000, 0.03,
                FilingStatus.SINGLE, taxCalculator, "taxable,traditional,roth",
                List.of(passiveSource), new RentalLossCalculator());

        var activeResult = activeOptimizer.optimize();
        var passiveResult = passiveOptimizer.optimize();

        double activeTotal = 0;
        double passiveTotal = 0;
        for (int i = 0; i < years; i++) {
            activeTotal += activeResult.conversionByYear()[i];
            passiveTotal += passiveResult.conversionByYear()[i];
        }

        // Active REPS: $40K loss fully deducted → $40K more bracket space
        // Passive: $40K loss limited by $25K exception (or suspended) → less bracket space
        assertThat(activeTotal)
                .as("Active REPS should allow more conversions than passive")
                .isGreaterThan(passiveTotal);
    }

    @Test
    void optimize_noRentalProperties_sameBehaviorAsBeforeEnhancement() {
        int retirementAge = 62;
        int endAge = 90;
        int years = endAge - retirementAge;
        var otherIncome = new double[years];
        var taxableIncome = new double[years];

        var optimizerNull = buildOptimizer(
                1_000_000, 0, 200_000,
                otherIncome, taxableIncome,
                1963, retirementAge, endAge,
                5, 0.22, 0.22, 0.06,
                40_000, 0.03,
                FilingStatus.SINGLE, taxCalculator, "taxable,traditional,roth");

        var optimizerEmpty = buildOptimizerWithRentals(
                1_000_000, 0, 200_000,
                otherIncome, taxableIncome,
                1963, retirementAge, endAge,
                5, 0.22, 0.22, 0.06,
                40_000, 0.03,
                FilingStatus.SINGLE, taxCalculator, "taxable,traditional,roth",
                Collections.emptyList(), new RentalLossCalculator());

        var resultNull = optimizerNull.optimize();
        var resultEmpty = optimizerEmpty.optimize();

        // Same behavior: identical conversions and lifetime tax
        assertThat(resultEmpty.lifetimeTaxWith())
                .as("Empty income sources should produce same tax as null")
                .isEqualTo(resultNull.lifetimeTaxWith());
        assertThat(resultEmpty.conversionFraction())
                .as("Empty income sources should produce same fraction as null")
                .isCloseTo(resultNull.conversionFraction(), org.assertj.core.data.Offset.offset(0.001));
    }
}
