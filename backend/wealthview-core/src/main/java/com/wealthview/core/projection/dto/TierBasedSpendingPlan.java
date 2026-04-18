package com.wealthview.core.projection.dto;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

public final class TierBasedSpendingPlan implements SpendingPlan {

    private static final int SCALE = 4;
    private static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

    private final BigDecimal essentialExpenses;
    private final BigDecimal discretionaryExpenses;
    private final List<SpendingTierData> spendingTiers;

    private TierBasedSpendingPlan(BigDecimal essentialExpenses, BigDecimal discretionaryExpenses,
                                   List<SpendingTierData> spendingTiers) {
        this.essentialExpenses = essentialExpenses;
        this.discretionaryExpenses = discretionaryExpenses;
        this.spendingTiers = spendingTiers;
    }

    public static TierBasedSpendingPlan of(BigDecimal essential, BigDecimal discretionary,
                                            List<SpendingTierData> tiers) {
        return new TierBasedSpendingPlan(essential, discretionary,
                tiers != null ? List.copyOf(tiers) : List.of());
    }

    public record SpendingTierData(String name, int startAge, Integer endAge,
                                    BigDecimal essentialExpenses, BigDecimal discretionaryExpenses) {}

    public BigDecimal essentialExpenses() {
        return essentialExpenses;
    }

    public BigDecimal discretionaryExpenses() {
        return discretionaryExpenses;
    }

    public List<SpendingTierData> spendingTiers() {
        return spendingTiers;
    }

    @Override
    public ResolvedYearSpending resolveYear(int year, int age, int yearsInRetirement,
                                             BigDecimal inflationRate, BigDecimal activeIncome) {
        var resolved = resolveSpending(age);
        BigDecimal inflationFactor = computeInflationFactor(age, yearsInRetirement, inflationRate);
        BigDecimal essential = resolved.essential().multiply(inflationFactor).setScale(SCALE, ROUNDING);
        BigDecimal discretionary = resolved.discretionary().multiply(inflationFactor).setScale(SCALE, ROUNDING);
        BigDecimal spendingNeed = essential.add(discretionary);
        BigDecimal portfolioNeed = spendingNeed.subtract(activeIncome).max(BigDecimal.ZERO);
        return new ResolvedYearSpending(portfolioNeed, spendingNeed, essential, discretionary);
    }

    public record ResolvedSpending(BigDecimal essential, BigDecimal discretionary) {}

    public ResolvedSpending resolveSpending(int age) {
        if (!spendingTiers.isEmpty()) {
            var matches = findMatchingTiers(age);

            if (matches.size() == 1) {
                return resolveFromSingleTier(matches.getFirst());
            }
            if (matches.size() >= 2) {
                return resolveFromOverlappingTiers(matches);
            }
            var gapResult = resolveFromGap(age);
            if (gapResult != null) {
                return gapResult;
            }
        }
        return new ResolvedSpending(essentialExpenses, discretionaryExpenses);
    }

    private List<SpendingTierData> findMatchingTiers(int age) {
        var matches = new ArrayList<SpendingTierData>();
        for (var tier : spendingTiers) {
            if (age >= tier.startAge() && (tier.endAge() == null || age <= tier.endAge())) {
                matches.add(tier);
            }
        }
        return matches;
    }

    private ResolvedSpending resolveFromSingleTier(SpendingTierData tier) {
        return new ResolvedSpending(tier.essentialExpenses(), tier.discretionaryExpenses());
    }

    private ResolvedSpending resolveFromOverlappingTiers(List<SpendingTierData> matches) {
        var essSum = BigDecimal.ZERO;
        var discSum = BigDecimal.ZERO;
        for (var tier : matches) {
            essSum = essSum.add(tier.essentialExpenses());
            discSum = discSum.add(tier.discretionaryExpenses());
        }
        var count = new BigDecimal(matches.size());
        return new ResolvedSpending(
                essSum.divide(count, SCALE, ROUNDING),
                discSum.divide(count, SCALE, ROUNDING));
    }

    private ResolvedSpending resolveFromGap(int age) {
        SpendingTierData prev = null;
        SpendingTierData next = null;
        for (var tier : spendingTiers) {
            if (tier.endAge() != null && tier.endAge() < age) {
                if (prev == null || tier.endAge() > prev.endAge()) {
                    prev = tier;
                }
            }
            if (tier.startAge() > age) {
                if (next == null || tier.startAge() < next.startAge()) {
                    next = tier;
                }
            }
        }

        if (prev != null && next != null) {
            var TWO = new BigDecimal("2");
            var essential = prev.essentialExpenses().add(next.essentialExpenses())
                    .divide(TWO, SCALE, ROUNDING);
            var discretionary = prev.discretionaryExpenses().add(next.discretionaryExpenses())
                    .divide(TWO, SCALE, ROUNDING);
            return new ResolvedSpending(essential, discretionary);
        }
        return null;
    }

    public int computeYearsInTier(int age, int yearsInRetirement) {
        if (!spendingTiers.isEmpty()) {
            var matches = new ArrayList<SpendingTierData>();
            for (var tier : spendingTiers) {
                if (age >= tier.startAge() && (tier.endAge() == null || age <= tier.endAge())) {
                    matches.add(tier);
                }
            }
            if (matches.size() >= 2) {
                return 0;
            }
            if (matches.size() == 1) {
                var tier = matches.getFirst();
                int retirementStartAge = age - yearsInRetirement + 1;
                int effectiveTierStart = Math.max(tier.startAge(), retirementStartAge);
                return age - effectiveTierStart;
            }
        }
        return -1;
    }

    public BigDecimal computeInflationFactor(int age, int yearsInRetirement, BigDecimal inflationRate) {
        int yearsInTier = computeYearsInTier(age, yearsInRetirement);
        if (yearsInTier >= 0) {
            return yearsInTier > 0
                    ? BigDecimal.ONE.add(inflationRate).pow(yearsInTier)
                    : BigDecimal.ONE;
        }
        return yearsInRetirement > 1
                ? BigDecimal.ONE.add(inflationRate).pow(yearsInRetirement - 1)
                : BigDecimal.ONE;
    }
}
