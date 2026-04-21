package com.wealthview.core.projection.dto;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TierBasedSpendingPlanTest {

    @Test
    void conversionSchedule_onTierBasedPlan_returnsEmpty() {
        SpendingPlan plan = TierBasedSpendingPlan.of(
                new BigDecimal("40000"), new BigDecimal("20000"), List.of());

        assertThat(plan.conversionSchedule()).isEmpty();
    }


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

    @Test
    void accessors_exposeConfiguredValues() {
        var tiers = List.of(tier("Go-Go", 65, 74, "35000", "25000"));
        var plan = TierBasedSpendingPlan.of(
                new BigDecimal("40000"), new BigDecimal("20000"), tiers);

        assertThat(plan.essentialExpenses()).isEqualByComparingTo(new BigDecimal("40000"));
        assertThat(plan.discretionaryExpenses()).isEqualByComparingTo(new BigDecimal("20000"));
        assertThat(plan.spendingTiers()).hasSize(1).first()
                .extracting(TierBasedSpendingPlan.SpendingTierData::name).isEqualTo("Go-Go");
    }

    @Test
    void of_nullTiers_yieldsEmptyTierList() {
        var plan = TierBasedSpendingPlan.of(
                new BigDecimal("40000"), new BigDecimal("20000"), null);

        assertThat(plan.spendingTiers()).isEmpty();
    }

    @Test
    void resolveSpending_ageBeforeAllTiers_fallsBackToBase() {
        // Gap with no prev tier → resolveFromGap returns null → falls through to base
        var tiers = List.of(tier("Go-Go", 65, 74, "35000", "25000"));
        var plan = TierBasedSpendingPlan.of(
                new BigDecimal("40000"), new BigDecimal("20000"), tiers);

        var resolved = plan.resolveSpending(60);

        assertThat(resolved.essential()).isEqualByComparingTo(new BigDecimal("40000"));
        assertThat(resolved.discretionary()).isEqualByComparingTo(new BigDecimal("20000"));
    }

    @Test
    void resolveSpending_ageAfterFinalClosedTier_fallsBackToBase() {
        // Gap with no next tier → resolveFromGap returns null → falls through to base
        var tiers = List.of(tier("Go-Go", 65, 74, "35000", "25000"));
        var plan = TierBasedSpendingPlan.of(
                new BigDecimal("40000"), new BigDecimal("20000"), tiers);

        var resolved = plan.resolveSpending(80);

        assertThat(resolved.essential()).isEqualByComparingTo(new BigDecimal("40000"));
        assertThat(resolved.discretionary()).isEqualByComparingTo(new BigDecimal("20000"));
    }

    @Test
    void resolveSpending_gapSelectsMostRecentPrevAndEarliestNext() {
        // Tiers with gaps — confirms the tightest-neighbor logic runs
        var tiers = List.of(
                tier("Early", 60, 62, "10000", "5000"),
                tier("Mid", 65, 67, "30000", "15000"),
                tier("Late", 80, 90, "20000", "10000"));
        var plan = TierBasedSpendingPlan.of(
                new BigDecimal("99999"), new BigDecimal("99999"), tiers);

        // Age 70: gap → prev = Mid (endAge=67 closest to 70), next = Late (startAge=80 earliest > 70)
        var resolved = plan.resolveSpending(70);

        // essential = (30000 + 20000) / 2 = 25000; discretionary = (15000 + 10000) / 2 = 12500
        assertThat(resolved.essential()).isEqualByComparingTo(new BigDecimal("25000.0000"));
        assertThat(resolved.discretionary()).isEqualByComparingTo(new BigDecimal("12500.0000"));
    }

    @Test
    void resolveSpending_tierWithNullEndAge_matchesAllAgesBeyondStart() {
        var tiers = List.of(tier("OpenEnded", 75, null, "25000", "10000"));
        var plan = TierBasedSpendingPlan.of(
                new BigDecimal("40000"), new BigDecimal("20000"), tiers);

        var resolved = plan.resolveSpending(95);

        assertThat(resolved.essential()).isEqualByComparingTo(new BigDecimal("25000"));
    }

    @Test
    void computeYearsInTier_noTiers_returnsNegativeOne() {
        var plan = TierBasedSpendingPlan.of(
                new BigDecimal("40000"), new BigDecimal("20000"), List.of());

        assertThat(plan.computeYearsInTier(70, 5)).isEqualTo(-1);
    }

    @Test
    void computeYearsInTier_overlappingTiers_returnsZero() {
        var tiers = List.of(
                tier("A", 65, 75, "30000", "10000"),
                tier("B", 75, 84, "25000", "8000"));
        var plan = TierBasedSpendingPlan.of(
                new BigDecimal("40000"), new BigDecimal("20000"), tiers);

        assertThat(plan.computeYearsInTier(75, 10)).isZero();
    }

    @Test
    void resolveSpending_gapWithNullEndAgeTierPresent_skipsItForPrevSelection() {
        // Null-endAge tier is ignored when collecting the "prev" neighbor (no closed boundary to compare).
        // A: closed (50-55), B: open (80+), age 70 → gap → prev=A, next=B
        var tiers = List.of(
                tier("Closed", 50, 55, "10000", "5000"),
                tier("Open", 80, null, "40000", "20000"));
        var plan = TierBasedSpendingPlan.of(
                new BigDecimal("99999"), new BigDecimal("99999"), tiers);

        var resolved = plan.resolveSpending(70);

        // (10000 + 40000) / 2 = 25000; (5000 + 20000) / 2 = 12500
        assertThat(resolved.essential()).isEqualByComparingTo(new BigDecimal("25000.0000"));
        assertThat(resolved.discretionary()).isEqualByComparingTo(new BigDecimal("12500.0000"));
    }

    @Test
    void resolveSpending_gapRetainsTightestPrevAndNextWhenLaterCandidatesAreWorse() {
        // Tiers: A (ends 55), B (ends 50), C (starts 70), D (starts 80)
        // At age 60: prev candidates are A (55) and B (50) — A wins (tightest).
        // At age 60: next candidates are C (70) and D (80) — C wins.
        // Exercises the "prev != null AND new candidate is worse" and same for next branches.
        var tiers = List.of(
                tier("A", 40, 55, "10000", "5000"),
                tier("B", 30, 50, "20000", "7500"),
                tier("C", 70, 75, "30000", "10000"),
                tier("D", 80, 85, "40000", "12500"));
        var plan = TierBasedSpendingPlan.of(
                new BigDecimal("99999"), new BigDecimal("99999"), tiers);

        var resolved = plan.resolveSpending(60);

        // Picks A (prev, ends 55) and C (next, starts 70): (10000+30000)/2 = 20000, (5000+10000)/2 = 7500
        assertThat(resolved.essential()).isEqualByComparingTo(new BigDecimal("20000.0000"));
        assertThat(resolved.discretionary()).isEqualByComparingTo(new BigDecimal("7500.0000"));
    }

    @Test
    void computeInflationFactor_tierStartBeforeRetirement_inflatesFromRetirementStart() {
        // Tier starts at 50, but retirement begins at 65 → effectiveTierStart = 65
        // At age 68, yearsInRetirement=4: effective = max(50, 68-4+1=65) = 65, yearsInTier=3
        var tiers = List.of(tier("Broad", 50, 90, "30000", "10000"));
        var plan = TierBasedSpendingPlan.of(
                new BigDecimal("0"), new BigDecimal("0"), tiers);

        var factor = plan.computeInflationFactor(68, 4, INFLATION);

        assertThat(factor).isEqualByComparingTo(new BigDecimal("1.03").pow(3));
    }
}
