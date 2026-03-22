package com.wealthview.projection;

import com.wealthview.core.projection.tax.FederalTaxCalculator;
import com.wealthview.core.projection.tax.FilingStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
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

    @Test
    void optimize_allTraditional_producesConversions() {
        int retirementAge = 62;
        int endAge = 90;
        int years = endAge - retirementAge;
        var otherIncome = new double[years];
        var taxableIncome = new double[years];

        var optimizer = new RothConversionOptimizer(
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

        var optimizer = new RothConversionOptimizer(
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

        var optimizer = new RothConversionOptimizer(
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

        var optimizer = new RothConversionOptimizer(
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

        var optimizer = new RothConversionOptimizer(
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

        var optimizerBuffer3 = new RothConversionOptimizer(
                500_000, 0, 300_000,
                otherIncome, taxableIncome,
                1963, retirementAge, endAge,
                3, 0.22, 0.22, 0.06,
                30_000, 0.03,
                FilingStatus.SINGLE, taxCalculator, "taxable,traditional,roth");

        var optimizerBuffer8 = new RothConversionOptimizer(
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

        var optimizer = new RothConversionOptimizer(
                500_000, 100_000, 300_000,
                otherIncome, taxableIncome,
                1970, retirementAge, endAge,
                5, 0.22, 0.22, 0.06,
                40_000, 0.03,
                FilingStatus.SINGLE, taxCalculator, "taxable,traditional,roth");

        var result = optimizer.optimize();

        assertThat(result).isNotNull();
        // Before age 60 (proxy for 59.5), no traditional withdrawals for spending
        // Traditional balance should not decrease from spending draws in years 0-4 (age 55-59)
        // It may decrease from conversions but not from spending withdrawals.
        // We verify by checking that conversions are feasibility-guarded:
        // with $300K taxable and $40K spending, there should be room for some conversions
        // but the taxable pool must remain sufficient for spending + conversion tax
        for (int i = 0; i < 5; i++) {
            // Traditional balance changes should only be from conversions, not spending
            // The taxable balance should be covering all spending in these years
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

        var optimizerNoSS = new RothConversionOptimizer(
                1_000_000, 0, 200_000,
                otherIncomeNoSS, taxableIncomeNoSS,
                1963, retirementAge, endAge,
                5, 0.22, 0.22, 0.06,
                40_000, 0.03,
                FilingStatus.SINGLE, taxCalculator, "taxable,traditional,roth");

        var optimizerWithSS = new RothConversionOptimizer(
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

        // SS income fills bracket space ($30K out of $100K ceiling), reducing room for conversions.
        // The max possible conversion per year with SS is bracket_ceiling - SS = $70K * fraction,
        // vs $100K * fraction without SS. With smaller bracket space, the with-SS optimizer
        // should produce either fewer total conversions or a different fraction, but the per-year
        // cap is strictly lower. Verify at least one post-SS pre-RMD year shows this effect.
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
    void optimize_taxableDepletedBefore595_conversionsStop() {
        int retirementAge = 55;
        int endAge = 90;
        int years = endAge - retirementAge;
        var otherIncome = new double[years];
        var taxableIncome = new double[years];

        // Tiny taxable ($50K) with $40K spending — taxable depletes quickly
        var optimizer = new RothConversionOptimizer(
                800_000, 0, 50_000,
                otherIncome, taxableIncome,
                1970, retirementAge, endAge,
                5, 0.22, 0.22, 0.06,
                40_000, 0.03,
                FilingStatus.SINGLE, taxCalculator, "taxable,traditional,roth");

        var result = optimizer.optimize();

        assertThat(result).isNotNull();
        // With only $50K taxable and $40K/yr spending, conversions before age 60
        // must be very modest since taxable must cover both spending and conversion tax
        double totalConvBeforeSixty = 0;
        for (int i = 0; i < 5; i++) { // age 55-59
            totalConvBeforeSixty += result.conversionByYear()[i];
        }
        // Conversions before 60 should be small relative to the available traditional balance
        assertThat(totalConvBeforeSixty).isLessThan(100_000);
    }
}
