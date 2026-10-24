package com.wealthview.projection;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.wealthview.core.projection.dto.GuardrailSpendingInput;
import com.wealthview.core.projection.dto.GuardrailYearlySpending;
import com.wealthview.core.projection.dto.TierBasedSpendingPlan;

import static org.assertj.core.api.Assertions.assertThat;

class FixedSpendingScheduleTest {

    private static final BigDecimal INFLATION = new BigDecimal("0.025");

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }

    private static TierBasedSpendingPlan tierPlan() {
        return TierBasedSpendingPlan.of(bd("0"), bd("0"), List.of(
                new TierBasedSpendingPlan.SpendingTierData("early", 60, 74, bd("40000"), bd("20000")),
                new TierBasedSpendingPlan.SpendingTierData("late", 75, null, bd("45000"), bd("0"))));
    }

    private static TrialSimulator.HouseholdSim householdAt(int transitionIdx) {
        return new TrialSimulator.HouseholdSim(0, 0, 0, 75, transitionIdx, true, 1.0, 100,
                new TrialSimulator.TaxableSeed(0, 0, 0, 0, 0, 0));
    }

    private static GuardrailSpendingInput guardrailPlan(Map<Integer, BigDecimal> conversions) {
        var years = new java.util.ArrayList<GuardrailYearlySpending>();
        for (int y = 0; y < 5; y++) {
            years.add(new GuardrailYearlySpending(2030 + y, 65 + y, bd("50000"), bd("0"), bd("0"), bd("0"),
                    bd("0"), bd("0"), bd("0"), "p"));
        }
        return new GuardrailSpendingInput(years, conversions);
    }

    @Test
    void of_tierPlan_resolvesEachRetirementYearByAge() {
        var schedule = FixedSpendingSchedule.of(tierPlan(), 2030, 65, 12, INFLATION, null, 0.75);

        assertThat(schedule.spending()).containsExactly(60_000, 60_000, 60_000, 60_000, 60_000, 60_000, 60_000,
                60_000, 60_000, 60_000, 45_000, 45_000);
    }

    @Test
    void of_tierPlan_scalesFromTheFirstDeath() {
        var schedule = FixedSpendingSchedule.of(tierPlan(), 2030, 65, 6, INFLATION, householdAt(3), 0.75);

        assertThat(schedule.spending()).containsExactly(60_000, 60_000, 60_000, 45_000, 45_000, 45_000);
    }

    @Test
    void of_guardrailPlan_readsRecommendedByYearUnscaled() {
        var schedule = FixedSpendingSchedule.of(guardrailPlan(null), 2030, 65, 5, INFLATION, householdAt(3), 0.75);

        assertThat(schedule.spending()).containsExactly(50_000, 50_000, 50_000, 50_000, 50_000);
    }

    @Test
    void of_guardrailPlan_mapsItsConversionScheduleByYear() {
        var schedule = FixedSpendingSchedule.of(guardrailPlan(Map.of(2031, bd("20000"))), 2030, 65, 5, INFLATION,
                null, 1.0);

        assertThat(schedule.conversionByYear()).containsExactly(0, 20_000, 0, 0, 0);
    }

    @Test
    void of_tierPlan_hasNoConversions() {
        var schedule = FixedSpendingSchedule.of(tierPlan(), 2030, 65, 5, INFLATION, null, 1.0);

        assertThat(schedule.conversionByYear()).isNull();
    }
}
