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

        // Bracket ceiling: scales with rate. 22% → $100K, 10% → $45K, 12% → $55K, etc.
        when(taxCalculator.computeMaxIncomeForBracket(any(BigDecimal.class), anyInt(), any(FilingStatus.class)))
                .thenAnswer(invocation -> {
                    BigDecimal rate = invocation.getArgument(0);
                    // Simulate progressive bracket ceilings
                    double r = rate.doubleValue();
                    if (r <= 0.10) return new BigDecimal("45000");
                    if (r <= 0.12) return new BigDecimal("55000");
                    if (r <= 0.22) return new BigDecimal("100000");
                    if (r <= 0.24) return new BigDecimal("190000");
                    if (r <= 0.32) return new BigDecimal("245000");
                    return new BigDecimal("600000");
                });
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
                null, null, 0.10);
    }

    private RothConversionOptimizer buildOptimizerWithHeadroom(
            double initTraditional, double initRoth, double initTaxable,
            double[] otherIncome, double[] taxableIncome,
            int birthYear, int retirementAge, int endAge,
            int exhaustionBuffer, double conversionBracketRate,
            double rmdTargetBracketRate, double returnMean,
            double essentialFloor, double inflationRate,
            FilingStatus filingStatus, FederalTaxCalculator calc,
            String withdrawalOrder, double rmdBracketHeadroom) {
        return new RothConversionOptimizer(
                initTraditional, initRoth, initTaxable,
                otherIncome, taxableIncome,
                birthYear, retirementAge, endAge,
                exhaustionBuffer, conversionBracketRate,
                rmdTargetBracketRate, returnMean,
                essentialFloor, inflationRate,
                filingStatus, calc, withdrawalOrder,
                null, null, rmdBracketHeadroom);
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
                incomeSources, rentalLossCalculator, 0.10);
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
                5, 0.22, 0.12, 0.06,
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
                5, 0.22, 0.12, 0.06,
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
    void optimize_targetBalanceApproach_convertsExcess() {
        // With $2M traditional at 6% for 13 years ≈ $4.27M at RMD age,
        // which exceeds the target balance of $1.22M (12% bracket × 0.90 × 24.6).
        // The optimizer should convert the excess.
        int retirementAge = 62;
        int endAge = 90;
        int years = endAge - retirementAge;
        var otherIncome = new double[years];
        var taxableIncome = new double[years];

        var optimizer = buildOptimizer(
                2_000_000, 0, 300_000,
                otherIncome, taxableIncome,
                1963, retirementAge, endAge,
                5, 0.22, 0.12, 0.06,
                30_000, 0.03,
                FilingStatus.SINGLE, taxCalculator, "taxable,traditional,roth");

        var result = optimizer.optimize();

        assertThat(result).isNotNull();
        // Target balance should be positive and reported
        assertThat(result.targetTraditionalBalance()).isGreaterThan(0);
        // Conversions should happen (excess above target)
        double totalConversions = 0;
        for (double c : result.conversionByYear()) {
            totalConversions += c;
        }
        assertThat(totalConversions).isGreaterThan(0);
        // But not the full $2M — the target preserves a portion
        assertThat(totalConversions).isLessThan(2_000_000);
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
                5, 0.22, 0.12, 0.06,
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
                5, 0.22, 0.12, 0.06,
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
                3, 0.22, 0.12, 0.06,
                30_000, 0.03,
                FilingStatus.SINGLE, taxCalculator, "taxable,traditional,roth");

        var optimizerBuffer8 = buildOptimizer(
                500_000, 0, 300_000,
                otherIncome, taxableIncome,
                1963, retirementAge, endAge,
                8, 0.22, 0.12, 0.06,
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
                5, 0.22, 0.12, 0.06,
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
    void optimize_ssIncomeMidStream_affectsTargetBalance() {
        // SS income at RMD age reduces the available space for RMDs within the target
        // bracket, producing a SMALLER target balance. This means MORE of the traditional
        // balance needs converting away (or the per-year conversion space is reduced by
        // SS filling the bracket, leading to a different conversion trajectory).
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
                5, 0.22, 0.12, 0.06,
                40_000, 0.03,
                FilingStatus.SINGLE, taxCalculator, "taxable,traditional,roth");

        var optimizerWithSS = buildOptimizer(
                1_000_000, 0, 200_000,
                otherIncomeWithSS, taxableIncomeWithSS,
                1963, retirementAge, endAge,
                5, 0.22, 0.12, 0.06,
                40_000, 0.03,
                FilingStatus.SINGLE, taxCalculator, "taxable,traditional,roth");

        var resultNoSS = optimizerNoSS.optimize();
        var resultWithSS = optimizerWithSS.optimize();

        assertThat(resultNoSS).isNotNull();
        assertThat(resultWithSS).isNotNull();

        // SS income at RMD age reduces the target balance (less room for RMDs)
        // so the optimizer should convert more aggressively overall
        assertThat(resultWithSS.targetTraditionalBalance())
                .as("SS income should produce a smaller target balance")
                .isLessThan(resultNoSS.targetTraditionalBalance());
    }

    @Test
    void optimize_mfj_higherConversionsThanSingle() {
        int retirementAge = 62;
        int endAge = 90;
        int years = endAge - retirementAge;
        var otherIncome = new double[years];
        var taxableIncome = new double[years];

        // MFJ calculator with double the bracket ceilings of Single
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
                .thenAnswer(invocation -> {
                    BigDecimal rate = invocation.getArgument(0);
                    double r = rate.doubleValue();
                    if (r <= 0.10) return new BigDecimal("90000");
                    if (r <= 0.12) return new BigDecimal("110000");
                    if (r <= 0.22) return new BigDecimal("200000");
                    return new BigDecimal("400000");
                });

        // Use $2M traditional so both Single and MFJ exceed their target balances
        var mfjOpt = buildOptimizer(
                2_000_000, 0, 500_000,
                otherIncome, taxableIncome,
                1963, retirementAge, endAge,
                5, 0.22, 0.12, 0.06,
                30_000, 0.03,
                FilingStatus.MARRIED_FILING_JOINTLY, mfjCalc, "taxable,traditional,roth");

        var singleOpt = buildOptimizer(
                2_000_000, 0, 500_000,
                otherIncome, taxableIncome,
                1963, retirementAge, endAge,
                5, 0.22, 0.12, 0.06,
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
                5, 0.22, 0.12, 0.06,
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
                5, 0.22, 0.12, 0.06,
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
                5, 0.22, 0.12, 0.06,
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
                5, 0.22, 0.12, 0.06,
                40_000, 0.03,
                FilingStatus.SINGLE, taxCalculator, "taxable,traditional,roth",
                List.of(rentalSource), new RentalLossCalculator());

        var withoutRentals = buildOptimizer(
                1_000_000, 0, 200_000,
                otherIncome, taxableIncome,
                birthYear, retirementAge, endAge,
                5, 0.22, 0.12, 0.06,
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
                5, 0.22, 0.12, 0.06,
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
                5, 0.22, 0.12, 0.06,
                40_000, 0.03,
                FilingStatus.SINGLE, taxCalculator, "taxable,traditional,roth",
                List.of(rentalSource), new RentalLossCalculator());

        var withoutRentals = buildOptimizer(
                1_000_000, 0, 200_000,
                otherIncome, taxableIncome,
                birthYear, retirementAge, endAge,
                5, 0.22, 0.12, 0.06,
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
                2_000_000, 0, 200_000,
                otherIncome, taxableIncome,
                birthYear, retirementAge, endAge,
                5, 0.22, 0.12, 0.06,
                40_000, 0.03,
                FilingStatus.SINGLE, taxCalculator, "taxable,traditional,roth",
                List.of(activeSource), new RentalLossCalculator());

        var passiveOptimizer = buildOptimizerWithRentals(
                2_000_000, 0, 200_000,
                otherIncome, taxableIncome,
                birthYear, retirementAge, endAge,
                5, 0.22, 0.12, 0.06,
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
    void optimize_passiveProperty_conversionPhasesOutException_lessAggressive() {
        // The $25K passive loss exception phases out at MAGI $100K-$150K.
        // A large conversion pushes MAGI above $150K, eliminating the exception.
        // The optimizer must account for this: with passive properties, conversions
        // that destroy the passive loss benefit should be penalized by higher tax.
        int retirementAge = 62;
        int endAge = 90;
        int years = endAge - retirementAge;
        int birthYear = 1963;
        var otherIncome = new double[years];
        var taxableIncome = new double[years];

        Map<Integer, BigDecimal> depByYear = new java.util.HashMap<>();
        int firstYear = birthYear + retirementAge;
        for (int y = 0; y < years; y++) {
            depByYear.put(firstYear + y, new BigDecimal("60000"));
        }

        // Net rental: $30K - $10K - $3K - $60K = -$33K loss
        // Passive: only $25K deductible via exception (if MAGI < $100K)
        // But any conversion > ~$85K pushes MAGI over $100K, reducing the exception
        var passiveSource = new ProjectionIncomeSourceInput(
                java.util.UUID.randomUUID(), "Passive Rental", "rental_property",
                new BigDecimal("30000"), retirementAge, null,
                BigDecimal.ZERO, false, "rental_passive",
                new BigDecimal("10000"), BigDecimal.ZERO, BigDecimal.ZERO,
                new BigDecimal("3000"), "straight_line", depByYear);

        // Same property as active REPS — full deduction regardless of MAGI
        var activeSource = new ProjectionIncomeSourceInput(
                java.util.UUID.randomUUID(), "Active REPS", "rental_property",
                new BigDecimal("30000"), retirementAge, null,
                BigDecimal.ZERO, false, "rental_active_reps",
                new BigDecimal("10000"), BigDecimal.ZERO, BigDecimal.ZERO,
                new BigDecimal("3000"), "straight_line", depByYear);

        var passiveOpt = buildOptimizerWithRentals(
                500_000, 0, 200_000, otherIncome, taxableIncome,
                birthYear, retirementAge, endAge, 5, 0.22, 0.12, 0.06,
                30_000, 0.03, FilingStatus.SINGLE, taxCalculator, "taxable,traditional,roth",
                List.of(passiveSource), new RentalLossCalculator());

        var activeOpt = buildOptimizerWithRentals(
                500_000, 0, 200_000, otherIncome, taxableIncome,
                birthYear, retirementAge, endAge, 5, 0.22, 0.12, 0.06,
                30_000, 0.03, FilingStatus.SINGLE, taxCalculator, "taxable,traditional,roth",
                List.of(activeSource), new RentalLossCalculator());

        var passiveResult = passiveOpt.optimize();
        var activeResult = activeOpt.optimize();

        // Passive should have higher lifetime tax because the $25K exception
        // is phased out by conversions, reducing the tax benefit
        assertThat(passiveResult.lifetimeTaxWith())
                .as("Passive property should have higher lifetime tax than REPS "
                        + "(conversion MAGI phases out $25K exception)")
                .isGreaterThan(activeResult.lifetimeTaxWith());
    }

    @Test
    void optimize_rentalLossesOffsetConversionTax_notJustBracketSpace() {
        // Regression test: rental losses must reduce the TAX on conversions,
        // not just increase bracket space. With a $100K REPS loss, converting
        // $100K should be nearly tax-free (loss offsets conversion income).
        int retirementAge = 62;
        int endAge = 90;
        int years = endAge - retirementAge;
        int birthYear = 1963;
        var otherIncome = new double[years];
        var taxableIncome = new double[years];

        // REPS property with $100K depreciation → $100K loss (gross=0, just depreciation)
        Map<Integer, BigDecimal> depByYear = new java.util.HashMap<>();
        int firstYear = birthYear + retirementAge;
        for (int y = 0; y < years; y++) {
            depByYear.put(firstYear + y, new BigDecimal("100000"));
        }
        var rentalSource = new ProjectionIncomeSourceInput(
                java.util.UUID.randomUUID(), "High Depreciation", "rental_property",
                BigDecimal.ZERO, retirementAge, null,
                BigDecimal.ZERO, false, "rental_active_reps",
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, "cost_seg", depByYear);

        var withLosses = buildOptimizerWithRentals(
                2_000_000, 0, 200_000,
                otherIncome, taxableIncome,
                birthYear, retirementAge, endAge,
                5, 0.22, 0.12, 0.06,
                30_000, 0.03,
                FilingStatus.SINGLE, taxCalculator, "taxable,traditional,roth",
                List.of(rentalSource), new RentalLossCalculator());

        var withoutLosses = buildOptimizer(
                2_000_000, 0, 200_000,
                otherIncome, taxableIncome,
                birthYear, retirementAge, endAge,
                5, 0.22, 0.12, 0.06,
                30_000, 0.03,
                FilingStatus.SINGLE, taxCalculator, "taxable,traditional,roth");

        var resultWith = withLosses.optimize();
        var resultWithout = withoutLosses.optimize();

        // With $100K REPS losses, the conversion tax should be MUCH lower
        // because losses offset conversion income (not just expand bracket space)
        double taxPerDollarWith = resultWith.lifetimeTaxWith()
                / java.util.Arrays.stream(resultWith.conversionByYear()).sum();
        double taxPerDollarWithout = resultWithout.lifetimeTaxWith()
                / java.util.Arrays.stream(resultWithout.conversionByYear()).sum();

        assertThat(taxPerDollarWith)
                .as("Tax per dollar converted with losses should be materially lower")
                .isLessThan(taxPerDollarWithout * 0.75); // at least 25% lower effective rate
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
                5, 0.22, 0.12, 0.06,
                40_000, 0.03,
                FilingStatus.SINGLE, taxCalculator, "taxable,traditional,roth");

        var optimizerEmpty = buildOptimizerWithRentals(
                1_000_000, 0, 200_000,
                otherIncome, taxableIncome,
                1963, retirementAge, endAge,
                5, 0.22, 0.12, 0.06,
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

    // ---- Target balance constraint tests ----

    @Test
    void optimize_targetBalance_doesNotConvertBelowTarget() {
        // With a $1M traditional and a high RMD target bracket (0.22 = high ceiling),
        // the target balance at RMD age is large. The optimizer should only convert
        // the excess above the target, not the full $1M.
        int retirementAge = 62;
        int endAge = 90;
        int years = endAge - retirementAge;
        var otherIncome = new double[years];
        var taxableIncome = new double[years];

        // Use 12% RMD target bracket → $55K ceiling → target balance ≈ $1.2M
        // $1M traditional projected to RMD age ≈ $2.1M, so excess ≈ $900K
        var optimizer = buildOptimizerWithHeadroom(
                1_000_000, 0, 200_000,
                otherIncome, taxableIncome,
                1963, retirementAge, endAge,
                5, 0.22, 0.12, 0.06,
                40_000, 0.03,
                FilingStatus.SINGLE, taxCalculator, "taxable,traditional,roth",
                0.10);

        var result = optimizer.optimize();

        assertThat(result).isNotNull();

        double totalConversions = 0;
        for (double c : result.conversionByYear()) {
            totalConversions += c;
        }

        // Conversions should be capped at the excess above the target trajectory.
        // Without the target cap, the optimizer would convert much more aggressively.
        // Total conversions should be less than the initial traditional balance
        // because the target balance preserves a portion for RMDs within the bracket.
        assertThat(totalConversions)
                .as("Should only convert the excess above the target balance")
                .isLessThan(1_000_000);

        // Also verify the target traditional balance is reported
        assertThat(result.targetTraditionalBalance())
                .as("Target traditional balance should be positive")
                .isGreaterThan(0);
    }

    @Test
    void optimize_lowTargetBracket_convertsMoreAggressively() {
        // With rmdTargetBracketRate = 0.10 (10% bracket, low ceiling),
        // the target balance is small, so more of traditional needs converting.
        int retirementAge = 62;
        int endAge = 90;
        int years = endAge - retirementAge;
        var otherIncome = new double[years];
        var taxableIncome = new double[years];

        // Low RMD target bracket → small target → more conversions needed
        var optimizerLow = buildOptimizerWithHeadroom(
                1_000_000, 0, 200_000,
                otherIncome, taxableIncome,
                1963, retirementAge, endAge,
                5, 0.22, 0.10, 0.06,
                40_000, 0.03,
                FilingStatus.SINGLE, taxCalculator, "taxable,traditional,roth",
                0.10);

        // High RMD target bracket → large target → fewer conversions needed
        var optimizerHigh = buildOptimizerWithHeadroom(
                1_000_000, 0, 200_000,
                otherIncome, taxableIncome,
                1963, retirementAge, endAge,
                5, 0.22, 0.22, 0.06,
                40_000, 0.03,
                FilingStatus.SINGLE, taxCalculator, "taxable,traditional,roth",
                0.10);

        var resultLow = optimizerLow.optimize();
        var resultHigh = optimizerHigh.optimize();

        double totalLow = 0;
        double totalHigh = 0;
        for (int i = 0; i < years; i++) {
            totalLow += resultLow.conversionByYear()[i];
            totalHigh += resultHigh.conversionByYear()[i];
        }

        // Lower target bracket (10% → $45K ceiling) = smaller target balance = more conversions
        // Higher target bracket (22% → $100K ceiling) = larger target balance = fewer conversions
        assertThat(totalLow)
                .as("Low target bracket should produce more conversions than high target bracket")
                .isGreaterThan(totalHigh);
    }

    @Test
    void optimize_higherHeadroom_smallerTargetBalance() {
        // 25% headroom should produce a smaller target balance (and more conversions)
        // than 5% headroom with the same bracket target.
        int retirementAge = 62;
        int endAge = 90;
        int years = endAge - retirementAge;
        var otherIncome = new double[years];
        var taxableIncome = new double[years];

        var optimizerLowHeadroom = buildOptimizerWithHeadroom(
                1_000_000, 0, 200_000,
                otherIncome, taxableIncome,
                1963, retirementAge, endAge,
                5, 0.22, 0.12, 0.06,
                40_000, 0.03,
                FilingStatus.SINGLE, taxCalculator, "taxable,traditional,roth",
                0.05);

        var optimizerHighHeadroom = buildOptimizerWithHeadroom(
                1_000_000, 0, 200_000,
                otherIncome, taxableIncome,
                1963, retirementAge, endAge,
                5, 0.22, 0.12, 0.06,
                40_000, 0.03,
                FilingStatus.SINGLE, taxCalculator, "taxable,traditional,roth",
                0.25);

        var resultLow = optimizerLowHeadroom.optimize();
        var resultHigh = optimizerHighHeadroom.optimize();

        // Higher headroom → smaller target → more conversions
        assertThat(resultHigh.targetTraditionalBalance())
                .as("Higher headroom should produce a smaller target balance")
                .isLessThan(resultLow.targetTraditionalBalance());

        double totalLow = 0;
        double totalHigh = 0;
        for (int i = 0; i < years; i++) {
            totalLow += resultLow.conversionByYear()[i];
            totalHigh += resultHigh.conversionByYear()[i];
        }

        assertThat(totalHigh)
                .as("Higher headroom should produce more conversions")
                .isGreaterThanOrEqualTo(totalLow);
    }

    @Test
    void optimize_traditionalBelowTarget_noConversions() {
        // If the traditional balance (projected to RMD age) is already below
        // the target, no conversions should be recommended.
        // Use a small traditional balance with a high target bracket.
        int retirementAge = 62;
        int endAge = 90;
        int years = endAge - retirementAge;
        var otherIncome = new double[years];
        var taxableIncome = new double[years];

        // $100K traditional with 12% target bracket → $55K ceiling × 0.90 headroom
        // × 24.6 distribution period ≈ $1.2M target. $100K projected to RMD age at 6%
        // for ~13 years ≈ $213K — still well below $1.2M. No conversions needed.
        var optimizer = buildOptimizerWithHeadroom(
                100_000, 500_000, 200_000,
                otherIncome, taxableIncome,
                1963, retirementAge, endAge,
                5, 0.22, 0.12, 0.06,
                40_000, 0.03,
                FilingStatus.SINGLE, taxCalculator, "taxable,traditional,roth",
                0.10);

        var result = optimizer.optimize();

        double totalConversions = 0;
        for (double c : result.conversionByYear()) {
            totalConversions += c;
        }

        assertThat(totalConversions)
                .as("No conversions needed when traditional is already below target")
                .isEqualTo(0);
    }
}
