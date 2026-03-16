package com.wealthview.core.projection.dto;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TierBasedSpendingPlanTest {

    private static final BigDecimal INFLATION = new BigDecimal("0.03");
    private static final int SCALE = 4;

    private static TierBasedSpendingPlan.SpendingTierData tier(String name, int startAge, Integer endAge,
                                                                String essential, String discretionary) {
        return new TierBasedSpendingPlan.SpendingTierData(name, startAge, endAge,
                new BigDecimal(essential), new BigDecimal(discretionary));
    }

    @Test
    void resolveYear_noTiers_usesBaseExpenses() {
        var plan = TierBasedSpendingPlan.of(
                new BigDecimal("40000"), new BigDecimal("20000"), List.of());

        var result = plan.resolveYear(2030, 65, 1, INFLATION, BigDecimal.ZERO);

        // First year in retirement (yearsInRetirement=1), no tiers → computeYearsInTier returns -1
        // yearsInRetirement=1 → inflation factor = 1 (not > 1)
        assertThat(result.totalSpending()).isEqualByComparingTo(new BigDecimal("60000.0000"));
        assertThat(result.portfolioWithdrawal()).isEqualByComparingTo(new BigDecimal("60000.0000"));
        assertThat(result.essential()).isEqualByComparingTo(new BigDecimal("40000.0000"));
        assertThat(result.discretionary()).isEqualByComparingTo(new BigDecimal("20000.0000"));
    }

    @Test
    void resolveYear_noTiers_secondYear_appliesInflation() {
        var plan = TierBasedSpendingPlan.of(
                new BigDecimal("40000"), new BigDecimal("20000"), List.of());

        var result = plan.resolveYear(2031, 66, 2, INFLATION, BigDecimal.ZERO);

        // yearsInRetirement=2, no tiers → inflation factor = 1.03^1 = 1.03
        BigDecimal expected = new BigDecimal("60000").multiply(new BigDecimal("1.03"))
                .setScale(SCALE, RoundingMode.HALF_UP);
        assertThat(result.totalSpending()).isEqualByComparingTo(expected);
    }

    @Test
    void resolveYear_singleMatchingTier_usesTierExpenses() {
        var tiers = List.of(
                tier("Go-Go", 65, 74, "35000", "25000"));
        var plan = TierBasedSpendingPlan.of(
                new BigDecimal("40000"), new BigDecimal("20000"), tiers);

        var result = plan.resolveYear(2030, 67, 3, INFLATION, BigDecimal.ZERO);

        // Tier matches (65 <= 67 <= 74). retirementStartAge = 67-3+1 = 65
        // effectiveTierStart = max(65, 65) = 65, yearsInTier = 67 - 65 = 2
        // inflation factor = 1.03^2
        BigDecimal factor = new BigDecimal("1.03").pow(2);
        BigDecimal expectedEss = new BigDecimal("35000").multiply(factor).setScale(SCALE, RoundingMode.HALF_UP);
        BigDecimal expectedDisc = new BigDecimal("25000").multiply(factor).setScale(SCALE, RoundingMode.HALF_UP);
        BigDecimal expectedTotal = expectedEss.add(expectedDisc);

        assertThat(result.totalSpending()).isEqualByComparingTo(expectedTotal);
        assertThat(result.portfolioWithdrawal()).isEqualByComparingTo(expectedTotal);
    }

    @Test
    void resolveYear_overlappingTiers_blends() {
        var tiers = List.of(
                tier("Go-Go", 65, 75, "35000", "25000"),
                tier("Slow-Go", 75, 84, "30000", "15000"));
        var plan = TierBasedSpendingPlan.of(
                new BigDecimal("40000"), new BigDecimal("20000"), tiers);

        // At age 75, both tiers match → blend 50/50
        var result = plan.resolveYear(2030, 75, 1, BigDecimal.ZERO, BigDecimal.ZERO);

        // Overlap → yearsInTier = 0 → inflation factor = 1
        // essential = (35000+30000)/2 = 32500, discretionary = (25000+15000)/2 = 20000
        assertThat(result.totalSpending()).isEqualByComparingTo(new BigDecimal("52500.0000"));
    }

    @Test
    void resolveYear_gapBetweenTiers_blendsPrevAndNext() {
        var tiers = List.of(
                tier("Go-Go", 65, 73, "35000", "25000"),
                tier("Slow-Go", 76, 84, "30000", "15000"));
        var plan = TierBasedSpendingPlan.of(
                new BigDecimal("40000"), new BigDecimal("20000"), tiers);

        // At age 74, no tier matches → gap → blend prev(Go-Go) and next(Slow-Go)
        var result = plan.resolveYear(2030, 74, 1, BigDecimal.ZERO, BigDecimal.ZERO);

        // essential = (35000+30000)/2 = 32500, discretionary = (25000+15000)/2 = 20000
        assertThat(result.totalSpending()).isEqualByComparingTo(new BigDecimal("52500.0000"));
    }

    @Test
    void resolveYear_inflationResetsAtTierBoundary() {
        var tiers = List.of(
                tier("Go-Go", 65, 74, "35000", "25000"),
                tier("Slow-Go", 75, null, "30000", "15000"));
        var plan = TierBasedSpendingPlan.of(
                new BigDecimal("40000"), new BigDecimal("20000"), tiers);

        // Age 76, yearsInRetirement = 12 (retired at 65)
        // retirementStartAge = 76 - 12 + 1 = 65
        // effectiveTierStart = max(75, 65) = 75
        // yearsInTier = 76 - 75 = 1 → inflation factor = 1.03^1
        var result = plan.resolveYear(2041, 76, 12, INFLATION, BigDecimal.ZERO);

        BigDecimal factor = new BigDecimal("1.03");
        BigDecimal expectedEss = new BigDecimal("30000").multiply(factor).setScale(SCALE, RoundingMode.HALF_UP);
        BigDecimal expectedDisc = new BigDecimal("15000").multiply(factor).setScale(SCALE, RoundingMode.HALF_UP);
        BigDecimal expectedTotal = expectedEss.add(expectedDisc);

        assertThat(result.totalSpending()).isEqualByComparingTo(expectedTotal);
    }

    @Test
    void resolveYear_subtractsActiveIncome() {
        var plan = TierBasedSpendingPlan.of(
                new BigDecimal("40000"), new BigDecimal("20000"), List.of());

        var result = plan.resolveYear(2030, 65, 1, BigDecimal.ZERO, new BigDecimal("25000"));

        assertThat(result.totalSpending()).isEqualByComparingTo(new BigDecimal("60000.0000"));
        assertThat(result.portfolioWithdrawal()).isEqualByComparingTo(new BigDecimal("35000.0000"));
        assertThat(result.essential()).isEqualByComparingTo(new BigDecimal("40000.0000"));
        assertThat(result.discretionary()).isEqualByComparingTo(new BigDecimal("20000.0000"));
    }

    @Test
    void resolveYear_activeIncomeExceedsSpending_portfolioWithdrawalIsZero() {
        var plan = TierBasedSpendingPlan.of(
                new BigDecimal("40000"), new BigDecimal("20000"), List.of());

        var result = plan.resolveYear(2030, 65, 1, BigDecimal.ZERO, new BigDecimal("80000"));

        assertThat(result.totalSpending()).isEqualByComparingTo(new BigDecimal("60000.0000"));
        assertThat(result.portfolioWithdrawal()).isEqualByComparingTo(BigDecimal.ZERO);
    }
}
