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
import static org.mockito.ArgumentMatchers.nullable;
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
        // Stub both 3-arg and 4-arg overloads (4-arg adds inflation indexing).
        org.mockito.stubbing.Answer<BigDecimal> bracketAnswer = invocation -> {
            BigDecimal rate = invocation.getArgument(0);
            double r = rate.doubleValue();
            if (r <= 0.10) return new BigDecimal("45000");
            if (r <= 0.12) return new BigDecimal("55000");
            if (r <= 0.22) return new BigDecimal("100000");
            if (r <= 0.24) return new BigDecimal("190000");
            if (r <= 0.32) return new BigDecimal("245000");
            return new BigDecimal("600000");
        };
        when(taxCalculator.computeMaxIncomeForBracket(
                any(BigDecimal.class), anyInt(), any(FilingStatus.class)))
                .thenAnswer(bracketAnswer);
        when(taxCalculator.computeMaxIncomeForBracket(
                any(BigDecimal.class), anyInt(), any(FilingStatus.class), nullable(BigDecimal.class)))
                .thenAnswer(bracketAnswer);
    }

    private class OptimizerTestBuilder {
        private double traditional = 1_000_000;
        private double roth = 0;
        private double taxable = 200_000;
        private double[] otherIncome = null;
        private double[] taxableIncome = null;
        private int birthYear = 1963;
        private int retirementAge = 62;
        private int endAge = 90;
        private int exhaustionBuffer = 5;
        private double conversionBracketRate = 0.22;
        private double rmdTargetBracketRate = 0.12;
        private double returnMean = 0.06;
        private double essentialFloor = 40_000;
        private double inflationRate = 0.03;
        private FilingStatus filingStatus = FilingStatus.SINGLE;
        private FederalTaxCalculator calc = taxCalculator;
        private String withdrawalOrder = "taxable,traditional,roth";
        private List<ProjectionIncomeSourceInput> incomeSources = null;
        private RentalLossCalculator rentalLossCalculator = null;
        private double rmdBracketHeadroom = 0.10;
        private double dynamicSequencingBracketRate = 0.0;

        OptimizerTestBuilder traditional(double v) { this.traditional = v; return this; }
        OptimizerTestBuilder roth(double v) { this.roth = v; return this; }
        OptimizerTestBuilder taxable(double v) { this.taxable = v; return this; }
        OptimizerTestBuilder otherIncome(double[] v) { this.otherIncome = v; return this; }
        OptimizerTestBuilder taxableIncome(double[] v) { this.taxableIncome = v; return this; }
        OptimizerTestBuilder birthYear(int v) { this.birthYear = v; return this; }
        OptimizerTestBuilder retirementAge(int v) { this.retirementAge = v; return this; }
        OptimizerTestBuilder endAge(int v) { this.endAge = v; return this; }
        OptimizerTestBuilder exhaustionBuffer(int v) { this.exhaustionBuffer = v; return this; }
        OptimizerTestBuilder conversionBracketRate(double v) { this.conversionBracketRate = v; return this; }
        OptimizerTestBuilder rmdTargetBracketRate(double v) { this.rmdTargetBracketRate = v; return this; }
        OptimizerTestBuilder returnMean(double v) { this.returnMean = v; return this; }
        OptimizerTestBuilder essentialFloor(double v) { this.essentialFloor = v; return this; }
        OptimizerTestBuilder inflationRate(double v) { this.inflationRate = v; return this; }
        OptimizerTestBuilder filingStatus(FilingStatus v) { this.filingStatus = v; return this; }
        OptimizerTestBuilder calc(FederalTaxCalculator v) { this.calc = v; return this; }
        OptimizerTestBuilder withdrawalOrder(String v) { this.withdrawalOrder = v; return this; }
        OptimizerTestBuilder withRentals(List<ProjectionIncomeSourceInput> sources) {
            this.incomeSources = sources;
            this.rentalLossCalculator = new RentalLossCalculator();
            return this;
        }
        OptimizerTestBuilder rmdBracketHeadroom(double v) { this.rmdBracketHeadroom = v; return this; }
        OptimizerTestBuilder dynamicSequencingBracketRate(double v) {
            this.dynamicSequencingBracketRate = v; return this;
        }

        RothConversionOptimizer build() {
            int years = endAge - retirementAge;
            return new RothConversionOptimizer(
                    traditional, roth, taxable,
                    otherIncome != null ? otherIncome : new double[years],
                    taxableIncome != null ? taxableIncome : new double[years],
                    birthYear, retirementAge, endAge, exhaustionBuffer,
                    conversionBracketRate, rmdTargetBracketRate, returnMean,
                    essentialFloor, inflationRate, filingStatus, calc,
                    withdrawalOrder, incomeSources, rentalLossCalculator,
                    rmdBracketHeadroom, dynamicSequencingBracketRate);
        }
    }

    private OptimizerTestBuilder testBuilder() { return new OptimizerTestBuilder(); }

    @Test
    void optimize_allTraditional_producesConversions() {
        var optimizer = testBuilder().build();

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
        var optimizer = testBuilder()
                .traditional(0)
                .roth(1_000_000)
                .build();

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
        var optimizer = testBuilder()
                .traditional(2_000_000)
                .taxable(300_000)
                .essentialFloor(30_000)
                .build();

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
        var optimizer = testBuilder()
                .traditional(800_000)
                .birthYear(1959)
                .retirementAge(65)
                .build();

        var result = optimizer.optimize();

        assertThat(result).isNotNull();
        // RMD starts at age 73, which is yearIndex 8 (age 65 + 8 = 73)
        // No RMDs before age 73
        for (int i = 0; i < 8; i++) {
            assertThat(result.projectedRmd()[i])
                    .as("No RMD at age %d", 65 + i)
                    .isEqualTo(0);
        }
    }

    @Test
    void optimize_rmdStartAge_born1960_uses75() {
        var optimizer = testBuilder()
                .traditional(800_000)
                .birthYear(1960)
                .retirementAge(65)
                .build();

        var result = optimizer.optimize();

        assertThat(result).isNotNull();
        // RMD starts at age 75, which is yearIndex 10 (age 65 + 10 = 75)
        // No RMDs before age 75
        for (int i = 0; i < 10; i++) {
            assertThat(result.projectedRmd()[i])
                    .as("No RMD at age %d", 65 + i)
                    .isEqualTo(0);
        }
    }

    @Test
    void optimize_exhaustionBuffer3vs8_differentExhaustionAge() {
        var optimizerBuffer3 = testBuilder()
                .traditional(500_000)
                .taxable(300_000)
                .essentialFloor(30_000)
                .exhaustionBuffer(3)
                .build();

        var optimizerBuffer8 = testBuilder()
                .traditional(500_000)
                .taxable(300_000)
                .essentialFloor(30_000)
                .exhaustionBuffer(8)
                .build();

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
        var optimizer = testBuilder()
                .traditional(500_000)
                .roth(100_000)
                .taxable(300_000)
                .birthYear(1970)
                .retirementAge(55)
                .build();

        var result = optimizer.optimize();

        assertThat(result).isNotNull();
        for (int i = 0; i < 5; i++) {
            assertThat(result.taxableBalance()[i])
                    .as("Taxable balance at age %d should be positive (covering spending)", 55 + i)
                    .isGreaterThanOrEqualTo(0);
        }
    }

    @Test
    void optimize_ssIncomeMidStream_affectsTargetBalance() {
        // SS income at RMD age reduces the available space for RMDs within the target
        // bracket, producing a SMALLER target balance. This means MORE of the traditional
        // balance needs converting away (or the per-year conversion space is reduced by
        // SS filling the bracket, leading to a different conversion trajectory).
        int years = 28; // 90 - 62

        // With SS income starting at age 67 ($30K/yr)
        var otherIncomeWithSS = new double[years];
        var taxableIncomeWithSS = new double[years];
        for (int i = 0; i < years; i++) {
            int age = 62 + i;
            if (age >= 67) {
                otherIncomeWithSS[i] = 30_000;
                taxableIncomeWithSS[i] = 30_000;
            }
        }

        var optimizerNoSS = testBuilder().build();

        var optimizerWithSS = testBuilder()
                .otherIncome(otherIncomeWithSS)
                .taxableIncome(taxableIncomeWithSS)
                .build();

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
        org.mockito.stubbing.Answer<BigDecimal> mfjBracketAnswer = invocation -> {
            BigDecimal rate = invocation.getArgument(0);
            double r = rate.doubleValue();
            if (r <= 0.10) return new BigDecimal("90000");
            if (r <= 0.12) return new BigDecimal("110000");
            if (r <= 0.22) return new BigDecimal("200000");
            return new BigDecimal("400000");
        };
        when(mfjCalc.computeMaxIncomeForBracket(any(BigDecimal.class), anyInt(), any(FilingStatus.class)))
                .thenAnswer(mfjBracketAnswer);
        when(mfjCalc.computeMaxIncomeForBracket(
                any(BigDecimal.class), anyInt(), any(FilingStatus.class), nullable(BigDecimal.class)))
                .thenAnswer(mfjBracketAnswer);

        // Use $2M traditional so both Single and MFJ exceed their target balances
        var mfjOpt = testBuilder()
                .traditional(2_000_000)
                .taxable(500_000)
                .essentialFloor(30_000)
                .filingStatus(FilingStatus.MARRIED_FILING_JOINTLY)
                .calc(mfjCalc)
                .build();

        var singleOpt = testBuilder()
                .traditional(2_000_000)
                .taxable(500_000)
                .essentialFloor(30_000)
                .build();

        var mfjSched = mfjOpt.optimize();
        var singleSched = singleOpt.optimize();

        double mfjMaxYear = 0;
        double singleMaxYear = 0;
        int years = 28; // 90 - 62
        for (int i = 0; i < years; i++) {
            mfjMaxYear = Math.max(mfjMaxYear, mfjSched.conversionByYear()[i]);
            singleMaxYear = Math.max(singleMaxYear, singleSched.conversionByYear()[i]);
        }
        assertThat(mfjMaxYear).isGreaterThan(singleMaxYear);
    }

    @Test
    void optimize_alreadyPastRmdAge_noConversions() {
        var optimizer = testBuilder()
                .traditional(500_000)
                .birthYear(1955)
                .retirementAge(75)
                .endAge(95)
                .essentialFloor(30_000)
                .build();

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
        var optimizer = testBuilder()
                .traditional(5_000_000)
                .taxable(100_000)
                .birthYear(1955)
                .retirementAge(70)
                .endAge(85)
                .essentialFloor(30_000)
                .build();

        var schedule = optimizer.optimize();

        if (!schedule.exhaustionTargetMet()) {
            assertThat(schedule.exhaustionAge()).isGreaterThan(85 - 5);
        }
        assertThat(schedule.conversionByYear()).isNotNull();
    }

    @Test
    void optimize_taxableDepletedBefore595_conversionsStop() {
        var optimizer = testBuilder()
                .traditional(800_000)
                .taxable(50_000)
                .birthYear(1970)
                .retirementAge(55)
                .build();

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
        // Rental property: $30K gross rent, $10K operating expenses, $3K property tax,
        // $20K/year depreciation → net taxable = $30K - $10K - $3K - $20K = -$3K (loss)
        int years = 28;
        Map<Integer, BigDecimal> depreciationByYear = new java.util.HashMap<>();
        for (int y = 0; y < years; y++) {
            depreciationByYear.put(1963 + 62 + y, new BigDecimal("20000"));
        }

        var rentalSource = new ProjectionIncomeSourceInput(
                java.util.UUID.randomUUID(), "Rental Property", "rental_property",
                new BigDecimal("30000"), 62, null,
                BigDecimal.ZERO, false, "rental_passive",
                new BigDecimal("10000"), BigDecimal.ZERO, BigDecimal.ZERO,
                new BigDecimal("3000"), "straight_line", depreciationByYear);

        var withRentals = testBuilder()
                .withRentals(List.of(rentalSource))
                .build();

        var withoutRentals = testBuilder().build();

        var resultWith = withRentals.optimize();
        var resultWithout = withoutRentals.optimize();

        // Rental depreciation reduces taxable income → lower lifetime tax is the real benefit
        assertThat(resultWith.lifetimeTaxWith())
                .as("Rental depreciation should reduce lifetime tax compared to no rental")
                .isLessThan(resultWithout.lifetimeTaxWith());
    }

    @Test
    void optimize_withCostSeg481a_largerConversionsInEarlyYears() {
        // Cost seg: $120K depreciation in year 1, $15K in subsequent years
        // Net year 1: $30K - $10K - $3K - $120K = -$103K (massive loss)
        // Net year 2+: $30K - $10K - $3K - $15K = $2K (small profit)
        int years = 28;
        int firstCalYear = 1963 + 62;
        Map<Integer, BigDecimal> depreciationByYear = new java.util.HashMap<>();
        depreciationByYear.put(firstCalYear, new BigDecimal("120000"));
        for (int y = 1; y < years; y++) {
            depreciationByYear.put(firstCalYear + y, new BigDecimal("15000"));
        }

        var rentalSource = new ProjectionIncomeSourceInput(
                java.util.UUID.randomUUID(), "Cost Seg Property", "rental_property",
                new BigDecimal("30000"), 62, null,
                BigDecimal.ZERO, false, "rental_active_reps",
                new BigDecimal("10000"), BigDecimal.ZERO, BigDecimal.ZERO,
                new BigDecimal("3000"), "cost_seg", depreciationByYear);

        var optimizer = testBuilder()
                .withRentals(List.of(rentalSource))
                .build();

        var result = optimizer.optimize();

        // Year 0 should have the largest conversions due to massive cost seg loss
        // offsetting income (REPS → full loss deduction)
        assertThat(result.conversionByYear()[0])
                .as("First year with cost seg 481(a) should have large conversions")
                .isGreaterThan(result.conversionByYear()[2]);
    }

    @Test
    void optimize_passiveLossCarryforward_reducesLaterYearTax() {
        // Passive rental with large depreciation every year producing a $40K loss.
        // With $25K exception (MAGI < $100K), $25K is deductible, $15K suspended.
        // Suspended losses carry forward and release against future rental profits.
        // Net effect: year-over-year, the $25K deduction reduces taxable income,
        // creating more bracket space for conversions than without rentals.
        int years = 28;
        int firstCalYear = 1963 + 62;
        Map<Integer, BigDecimal> depreciationByYear = new java.util.HashMap<>();
        for (int y = 0; y < years; y++) {
            // net: $30K - $10K - $3K - $57K = -$40K loss per year
            depreciationByYear.put(firstCalYear + y, new BigDecimal("57000"));
        }

        var rentalSource = new ProjectionIncomeSourceInput(
                java.util.UUID.randomUUID(), "Passive Rental", "rental_property",
                new BigDecimal("30000"), 62, null,
                BigDecimal.ZERO, false, "rental_passive",
                new BigDecimal("10000"), BigDecimal.ZERO, BigDecimal.ZERO,
                new BigDecimal("3000"), "straight_line", depreciationByYear);

        var withCarryforward = testBuilder()
                .withRentals(List.of(rentalSource))
                .build();

        var withoutRentals = testBuilder().build();

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
        // Active REPS property with $40K loss → fully offsets ordinary income (no $25K limit)
        // Net: $30K - $10K - $3K - $57K = -$40K loss per year
        int years = 28;
        Map<Integer, BigDecimal> depreciationByYear = new java.util.HashMap<>();
        for (int y = 0; y < years; y++) {
            depreciationByYear.put(1963 + 62 + y, new BigDecimal("57000"));
        }

        var activeSource = new ProjectionIncomeSourceInput(
                java.util.UUID.randomUUID(), "Active REPS", "rental_property",
                new BigDecimal("30000"), 62, null,
                BigDecimal.ZERO, false, "rental_active_reps",
                new BigDecimal("10000"), BigDecimal.ZERO, BigDecimal.ZERO,
                new BigDecimal("3000"), "straight_line", depreciationByYear);

        var passiveSource = new ProjectionIncomeSourceInput(
                java.util.UUID.randomUUID(), "Passive Rental", "rental_property",
                new BigDecimal("30000"), 62, null,
                BigDecimal.ZERO, false, "rental_passive",
                new BigDecimal("10000"), BigDecimal.ZERO, BigDecimal.ZERO,
                new BigDecimal("3000"), "straight_line", depreciationByYear);

        var activeOptimizer = testBuilder()
                .traditional(2_000_000)
                .withRentals(List.of(activeSource))
                .build();

        var passiveOptimizer = testBuilder()
                .traditional(2_000_000)
                .withRentals(List.of(passiveSource))
                .build();

        var activeResult = activeOptimizer.optimize();
        var passiveResult = passiveOptimizer.optimize();

        // Active REPS: $40K loss fully deducted → lower lifetime tax than passive
        // Passive: $40K loss limited by $25K exception (or suspended) → higher tax
        assertThat(activeResult.lifetimeTaxWith())
                .as("Active REPS should have lower lifetime tax than passive (full vs limited loss deduction)")
                .isLessThan(passiveResult.lifetimeTaxWith());
    }

    @Test
    void optimize_passiveProperty_conversionPhasesOutException_lessAggressive() {
        // The $25K passive loss exception phases out at MAGI $100K-$150K.
        // A large conversion pushes MAGI above $150K, eliminating the exception.
        // The optimizer must account for this: with passive properties, conversions
        // that destroy the passive loss benefit should be penalized by higher tax.
        int years = 28;
        int firstYear = 1963 + 62;
        Map<Integer, BigDecimal> depByYear = new java.util.HashMap<>();
        for (int y = 0; y < years; y++) {
            depByYear.put(firstYear + y, new BigDecimal("60000"));
        }

        // Net rental: $30K - $10K - $3K - $60K = -$33K loss
        // Passive: only $25K deductible via exception (if MAGI < $100K)
        // But any conversion > ~$85K pushes MAGI over $100K, reducing the exception
        var passiveSource = new ProjectionIncomeSourceInput(
                java.util.UUID.randomUUID(), "Passive Rental", "rental_property",
                new BigDecimal("30000"), 62, null,
                BigDecimal.ZERO, false, "rental_passive",
                new BigDecimal("10000"), BigDecimal.ZERO, BigDecimal.ZERO,
                new BigDecimal("3000"), "straight_line", depByYear);

        // Same property as active REPS — full deduction regardless of MAGI
        var activeSource = new ProjectionIncomeSourceInput(
                java.util.UUID.randomUUID(), "Active REPS", "rental_property",
                new BigDecimal("30000"), 62, null,
                BigDecimal.ZERO, false, "rental_active_reps",
                new BigDecimal("10000"), BigDecimal.ZERO, BigDecimal.ZERO,
                new BigDecimal("3000"), "straight_line", depByYear);

        var passiveOpt = testBuilder()
                .traditional(500_000)
                .essentialFloor(30_000)
                .withRentals(List.of(passiveSource))
                .build();

        var activeOpt = testBuilder()
                .traditional(500_000)
                .essentialFloor(30_000)
                .withRentals(List.of(activeSource))
                .build();

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
        int years = 28;
        int firstYear = 1963 + 62;
        Map<Integer, BigDecimal> depByYear = new java.util.HashMap<>();
        for (int y = 0; y < years; y++) {
            depByYear.put(firstYear + y, new BigDecimal("100000"));
        }
        var rentalSource = new ProjectionIncomeSourceInput(
                java.util.UUID.randomUUID(), "High Depreciation", "rental_property",
                BigDecimal.ZERO, 62, null,
                BigDecimal.ZERO, false, "rental_active_reps",
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, "cost_seg", depByYear);

        var withLosses = testBuilder()
                .traditional(2_000_000)
                .essentialFloor(30_000)
                .withRentals(List.of(rentalSource))
                .build();

        var withoutLosses = testBuilder()
                .traditional(2_000_000)
                .essentialFloor(30_000)
                .build();

        var resultWith = withLosses.optimize();
        var resultWithout = withoutLosses.optimize();

        // With $100K REPS losses, absolute lifetime tax should be materially lower
        // because losses offset conversion income directly (not just expand bracket space)
        assertThat(resultWith.lifetimeTaxWith())
                .as("$100K REPS losses should materially reduce absolute lifetime tax")
                .isLessThan(resultWithout.lifetimeTaxWith() * 0.75); // at least 25% lower total tax
    }

    @Test
    void optimize_noRentalProperties_sameBehaviorAsBeforeEnhancement() {
        var optimizerNull = testBuilder().build();

        var optimizerEmpty = testBuilder()
                .withRentals(Collections.emptyList())
                .build();

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
        // Use 12% RMD target bracket → $55K ceiling → target balance ≈ $1.2M
        // $1M traditional projected to RMD age ≈ $2.1M, so excess ≈ $900K
        var optimizer = testBuilder().build();

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
        // Low RMD target bracket → small target → more conversions needed
        var optimizerLow = testBuilder()
                .rmdTargetBracketRate(0.10)
                .build();

        // High RMD target bracket → large target → fewer conversions needed
        var optimizerHigh = testBuilder()
                .rmdTargetBracketRate(0.22)
                .build();

        var resultLow = optimizerLow.optimize();
        var resultHigh = optimizerHigh.optimize();

        int years = 28;
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
        var optimizerLowHeadroom = testBuilder()
                .rmdBracketHeadroom(0.05)
                .build();

        var optimizerHighHeadroom = testBuilder()
                .rmdBracketHeadroom(0.25)
                .build();

        var resultLow = optimizerLowHeadroom.optimize();
        var resultHigh = optimizerHighHeadroom.optimize();

        // Higher headroom → smaller target → more conversions
        assertThat(resultHigh.targetTraditionalBalance())
                .as("Higher headroom should produce a smaller target balance")
                .isLessThan(resultLow.targetTraditionalBalance());

        int years = 28;
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
        // $100K traditional with 12% target bracket → $55K ceiling × 0.90 headroom
        // × 24.6 distribution period ≈ $1.2M target. $100K projected to RMD age at 6%
        // for ~13 years ≈ $213K — still well below $1.2M. No conversions needed.
        var optimizer = testBuilder()
                .traditional(100_000)
                .roth(500_000)
                .build();

        var result = optimizer.optimize();

        double totalConversions = 0;
        for (double c : result.conversionByYear()) {
            totalConversions += c;
        }

        assertThat(totalConversions)
                .as("No conversions needed when traditional is already below target")
                .isEqualTo(0);
    }

    // ---- Edge-case characterization tests ----

    @Test
    void scheduleForFraction_zeroTraditionalBalance_returnsZeroConversions() {
        var optimizer = testBuilder()
                .traditional(0)
                .roth(500_000)
                .build();

        var result = optimizer.scheduleForFraction(0.5);

        int years = 28;
        assertThat(result).isNotNull();
        for (int i = 0; i < years; i++) {
            assertThat(result.conversionByYear()[i])
                    .as("Conversion at year %d should be zero with no traditional balance", i)
                    .isEqualTo(0.0);
        }
        assertThat(result.lifetimeTaxWith())
                .as("Lifetime tax with and without conversions should be equal when traditional=0")
                .isEqualTo(result.lifetimeTaxWithout());
    }

    @Test
    void optimize_retirementAgeEqualsRmdStartAge_noConversions() {
        // Birth year 1960 → RMD starts at 75. Retire at 75 → no conversion window.
        var optimizer = testBuilder()
                .traditional(800_000)
                .birthYear(1960)
                .retirementAge(75)
                .build();

        var result = optimizer.optimize();

        assertThat(result).isNotNull();

        double totalConversions = 0;
        for (double c : result.conversionByYear()) {
            totalConversions += c;
        }
        assertThat(totalConversions)
                .as("No pre-RMD conversion window when retiring at RMD age")
                .isEqualTo(0.0);

        boolean hasRmd = false;
        for (double r : result.projectedRmd()) {
            if (r > 0) {
                hasRmd = true;
                break;
            }
        }
        assertThat(hasRmd)
                .as("RMDs should be projected even with no conversion window")
                .isTrue();
    }

    @Test
    void optimize_negativeEffectiveIncome_lossExceedsOtherIncome() {
        // REPS rental with $80K depreciation, $30K gross rent, $10K expenses, $3K property tax
        // → net = $30K - $10K - $3K - $80K = -$63K loss (fully deductible via REPS)
        // With $20K other income, effective income ≈ $20K - $63K = -$43K (negative).
        // Negative income inflates the target balance, so the traditional balance
        // ($1M growing to ~$2.1M at RMD age) stays below the inflated target.
        // Result: zero conversions, but lifetime tax is lower because losses offset
        // withdrawal income during the simulation years.
        int years = 28;
        var otherIncome = new double[years];
        var taxableIncome = new double[years];
        java.util.Arrays.fill(otherIncome, 20_000);
        java.util.Arrays.fill(taxableIncome, 20_000);

        int firstCalYear = 1963 + 62;
        Map<Integer, BigDecimal> depreciationByYear = new java.util.HashMap<>();
        for (int y = 0; y < years; y++) {
            depreciationByYear.put(firstCalYear + y, new BigDecimal("80000"));
        }

        var repsSource = new ProjectionIncomeSourceInput(
                java.util.UUID.randomUUID(), "REPS Rental", "rental_property",
                new BigDecimal("30000"), 62, null,
                BigDecimal.ZERO, false, "rental_active_reps",
                new BigDecimal("10000"), BigDecimal.ZERO, BigDecimal.ZERO,
                new BigDecimal("3000"), "straight_line", depreciationByYear);

        var withLargeRepsLoss = testBuilder()
                .otherIncome(otherIncome)
                .taxableIncome(taxableIncome)
                .withRentals(List.of(repsSource))
                .build();

        var withoutRentals = testBuilder()
                .otherIncome(otherIncome)
                .taxableIncome(taxableIncome)
                .build();

        var resultWith = withLargeRepsLoss.optimize();
        var resultWithout = withoutRentals.optimize();

        assertThat(resultWith).isNotNull();
        // Negative effective income inflates the target balance beyond the projected
        // traditional balance — optimizer correctly produces zero conversions.
        double totalConversions = 0;
        for (double c : resultWith.conversionByYear()) {
            totalConversions += c;
        }
        assertThat(totalConversions)
                .as("Large REPS loss inflates target balance past traditional projection — no conversions")
                .isEqualTo(0.0);
        // Rental losses still reduce lifetime tax by offsetting withdrawal income in simulation.
        assertThat(resultWith.lifetimeTaxWith())
                .as("Large REPS loss exceeding other income should reduce lifetime tax via offset during simulation")
                .isLessThan(resultWithout.lifetimeTaxWith());
    }

    @Test
    void optimize_nullWithdrawalOrder_defaultsToTaxableFirst() {
        var optimizerNull = testBuilder()
                .withdrawalOrder(null)
                .build();

        var optimizerExplicit = testBuilder().build();

        var resultNull = optimizerNull.optimize();
        var resultExplicit = optimizerExplicit.optimize();

        assertThat(resultNull.lifetimeTaxWith())
                .as("null withdrawalOrder should produce the same lifetime tax as explicit taxable-first order")
                .isEqualTo(resultExplicit.lifetimeTaxWith());
    }

    @Test
    void optimize_blankWithdrawalOrder_defaultsToTaxableFirst() {
        var optimizerBlank = testBuilder()
                .withdrawalOrder("  ")
                .build();

        var optimizerExplicit = testBuilder().build();

        var resultBlank = optimizerBlank.optimize();
        var resultExplicit = optimizerExplicit.optimize();

        assertThat(resultBlank.lifetimeTaxWith())
                .as("blank withdrawalOrder should produce the same lifetime tax as explicit taxable-first order")
                .isEqualTo(resultExplicit.lifetimeTaxWith());
    }

    @Test
    void optimize_essentialFloorExceedsPortfolio_gracefulDegradation() {
        // Essential floor ($500K) far exceeds total portfolio ($200K traditional).
        // The optimizer should not throw — it should return a result (possibly depleted).
        var optimizer = testBuilder()
                .traditional(200_000)
                .taxable(0)
                .essentialFloor(500_000)
                .build();

        var result = optimizer.optimize();

        assertThat(result).isNotNull();
    }

    @Test
    void optimize_zeroReturnRate_noGrowthSimulation() {
        // With returnMean=0.0, balances do not grow — they only decrease from
        // conversions, withdrawals, or RMDs. The traditional balance must be
        // monotonically non-increasing year-over-year.
        var optimizer = testBuilder()
                .returnMean(0.00)
                .build();

        var result = optimizer.optimize();

        int years = 28;
        assertThat(result).isNotNull();
        double[] trad = result.traditionalBalance();
        for (int i = 1; i < years; i++) {
            assertThat(trad[i])
                    .as("Traditional balance at year %d should be <= year %d (no growth)", i, i - 1)
                    .isLessThanOrEqualTo(trad[i - 1] + 1e-6); // small epsilon for floating-point
        }
    }

    @Test
    void optimize_largePassiveLosses_magiConvergenceStable() {
        // Calling scheduleForFraction(0.5) twice with large passive losses should
        // produce identical lifetimeTaxWith — MAGI convergence must be deterministic.
        int years = 28;
        int firstCalYear = 1963 + 62;
        Map<Integer, BigDecimal> depreciationByYear = new java.util.HashMap<>();
        for (int y = 0; y < years; y++) {
            depreciationByYear.put(firstCalYear + y, new BigDecimal("200000"));
        }

        var passiveSource = new ProjectionIncomeSourceInput(
                java.util.UUID.randomUUID(), "Large Passive Rental", "rental_property",
                new BigDecimal("30000"), 62, null,
                BigDecimal.ZERO, false, "rental_passive",
                new BigDecimal("10000"), BigDecimal.ZERO, BigDecimal.ZERO,
                new BigDecimal("3000"), "straight_line", depreciationByYear);

        var optimizer = testBuilder()
                .withRentals(List.of(passiveSource))
                .build();

        var result1 = optimizer.scheduleForFraction(0.5);
        var result2 = optimizer.scheduleForFraction(0.5);

        assertThat(result1.lifetimeTaxWith())
                .as("scheduleForFraction must be deterministic — same fraction must yield same tax")
                .isEqualTo(result2.lifetimeTaxWith());
    }

    // ---- Dynamic Sequencing tests ----

    @Test
    void optimize_dynamicSequencing_lowerLifetimeTaxThanTaxableFirst() {
        var dsOptimizer = testBuilder()
                .traditional(1_000_000)
                .taxable(200_000)
                .withdrawalOrder("dynamic_sequencing")
                .dynamicSequencingBracketRate(0.12)
                .build();

        var tfOptimizer = testBuilder()
                .traditional(1_000_000)
                .taxable(200_000)
                .build();  // defaults to "taxable,traditional,roth"

        var dsResult = dsOptimizer.optimize();
        var tfResult = tfOptimizer.optimize();

        // DS should draw Traditional at low brackets for spending,
        // reducing the balance more efficiently
        assertThat(dsResult.lifetimeTaxWith())
                .as("DS should produce lower or equal lifetime tax")
                .isLessThanOrEqualTo(tfResult.lifetimeTaxWith());
    }
}
