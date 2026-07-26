package com.wealthview.core.projection.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.wealthview.core.projection.mortality.MortalityTable;

import static org.assertj.core.api.Assertions.assertThat;

class GuardrailOptimizationInputTest {

    /**
     * Every component gets a value unique within its own type, so a transposed pair of same-typed
     * builder fields (the failure mode a 42-component positional constructor invites, and a
     * near-miss this code has actually had) fails an assertion instead of compiling silently.
     */
    @Test
    void builder_everyComponentSet_roundTripsEachValueToItsOwnAccessor() {
        var retirementDate = LocalDate.of(2040, 5, 17);
        List<ProjectionAccountInput> accounts = List.of();
        List<ProjectionIncomeSourceInput> incomeSources = List.of();
        var phases = List.of(new GuardrailPhaseInput("go-go", 60, 70, 1));
        var mortalityTable = new MortalityTable(Map.of(65, 0.011), Map.of(65, 0.009));

        var input = GuardrailOptimizationInput.builder()
                .retirementDate(retirementDate)
                .birthYear(1975)
                .endAge(96)
                .inflationRate(new BigDecimal("0.0201"))
                .accounts(accounts)
                .incomeSources(incomeSources)
                .essentialFloor(new BigDecimal("1.01"))
                .terminalBalanceTarget(new BigDecimal("2.02"))
                .returnMean(new BigDecimal("3.03"))
                .trialCount(1200)
                .confidenceLevel(new BigDecimal("0.9404"))
                .phases(phases)
                .seed(424242L)
                .portfolioFloor(new BigDecimal("5.05"))
                .maxAnnualAdjustmentRate(new BigDecimal("6.06"))
                .phaseBlendYears(3)
                .cashReserveYears(4)
                .cashReturnRate(new BigDecimal("7.07"))
                .filingStatus("filing-status")
                .withdrawalOrder("withdrawal-order")
                .optimizeConversions(true)
                .conversionBracketRate(new BigDecimal("8.08"))
                .rmdTargetBracketRate(new BigDecimal("9.09"))
                .traditionalExhaustionBuffer(6)
                .rmdBracketHeadroom(new BigDecimal("10.10"))
                .dynamicSequencingBracketRate(new BigDecimal("11.11"))
                .dividendYield(new BigDecimal("12.12"))
                .feeRate(new BigDecimal("13.13"))
                .baseYear(2031)
                .includeDepressionYears(true)
                .interestYield(new BigDecimal("14.14"))
                .gateOnAdaptiveRules(false)
                .spouseBirthYear(1978)
                .primaryDeathAge(88)
                .spouseDeathAge(91)
                .survivorSpendingFactor(new BigDecimal("15.15"))
                .communityProperty(true)
                .stochasticMortality(Boolean.FALSE)
                .primarySex("primary-sex")
                .spouseSex("spouse-sex")
                .longevityConditionalAge(99)
                .mortalityTable(mortalityTable)
                .build();

        assertThat(input.retirementDate()).isEqualTo(retirementDate);
        assertThat(input.birthYear()).isEqualTo(1975);
        assertThat(input.endAge()).isEqualTo(96);
        assertThat(input.inflationRate()).isEqualByComparingTo("0.0201");
        assertThat(input.accounts()).isSameAs(accounts);
        assertThat(input.incomeSources()).isSameAs(incomeSources);
        assertThat(input.essentialFloor()).isEqualByComparingTo("1.01");
        assertThat(input.terminalBalanceTarget()).isEqualByComparingTo("2.02");
        assertThat(input.returnMean()).isEqualByComparingTo("3.03");
        assertThat(input.trialCount()).isEqualTo(1200);
        assertThat(input.confidenceLevel()).isEqualByComparingTo("0.9404");
        assertThat(input.phases()).isSameAs(phases);
        assertThat(input.seed()).isEqualTo(424242L);
        assertThat(input.portfolioFloor()).isEqualByComparingTo("5.05");
        assertThat(input.maxAnnualAdjustmentRate()).isEqualByComparingTo("6.06");
        assertThat(input.phaseBlendYears()).isEqualTo(3);
        assertThat(input.cashReserveYears()).isEqualTo(4);
        assertThat(input.cashReturnRate()).isEqualByComparingTo("7.07");
        assertThat(input.filingStatus()).isEqualTo("filing-status");
        assertThat(input.withdrawalOrder()).isEqualTo("withdrawal-order");
        assertThat(input.optimizeConversions()).isTrue();
        assertThat(input.conversionBracketRate()).isEqualByComparingTo("8.08");
        assertThat(input.rmdTargetBracketRate()).isEqualByComparingTo("9.09");
        assertThat(input.traditionalExhaustionBuffer()).isEqualTo(6);
        assertThat(input.rmdBracketHeadroom()).isEqualByComparingTo("10.10");
        assertThat(input.dynamicSequencingBracketRate()).isEqualByComparingTo("11.11");
        assertThat(input.dividendYield()).isEqualByComparingTo("12.12");
        assertThat(input.feeRate()).isEqualByComparingTo("13.13");
        assertThat(input.baseYear()).isEqualTo(2031);
        assertThat(input.includeDepressionYears()).isTrue();
        assertThat(input.interestYield()).isEqualByComparingTo("14.14");
        assertThat(input.gateOnAdaptiveRules()).isFalse();
        assertThat(input.spouseBirthYear()).isEqualTo(1978);
        assertThat(input.primaryDeathAge()).isEqualTo(88);
        assertThat(input.spouseDeathAge()).isEqualTo(91);
        assertThat(input.survivorSpendingFactor()).isEqualByComparingTo("15.15");
        assertThat(input.communityProperty()).isTrue();
        assertThat(input.stochasticMortality()).isFalse();
        assertThat(input.primarySex()).isEqualTo("primary-sex");
        assertThat(input.spouseSex()).isEqualTo("spouse-sex");
        assertThat(input.longevityConditionalAge()).isEqualTo(99);
        assertThat(input.mortalityTable()).isSameAs(mortalityTable);
    }

    /**
     * Mirrors the back-compat constructor's documented anchor: an unset {@code baseYear} means "as
     * if retirement starts today", i.e. the retirement year, so the income-deflation clock runs at
     * offset 0.
     */
    @Test
    void builder_baseYearUnset_defaultsToRetirementYear() {
        var input = GuardrailOptimizationInput.builder()
                .retirementDate(LocalDate.of(2038, 3, 1))
                .build();

        assertThat(input.baseYear()).isEqualTo(2038);
    }

    @Test
    void builder_householdAndMortalityFieldsUnset_leavesSinglePersonFixedDeathAnchor() {
        var input = GuardrailOptimizationInput.builder()
                .retirementDate(LocalDate.of(2038, 3, 1))
                .build();

        assertThat(input.spouseBirthYear()).isNull();
        assertThat(input.primaryDeathAge()).isNull();
        assertThat(input.spouseDeathAge()).isNull();
        assertThat(input.survivorSpendingFactor()).isNull();
        assertThat(input.communityProperty()).isFalse();
        assertThat(input.stochasticMortality()).isNull();
        assertThat(input.mortalityTable()).isNull();
        assertThat(input.includeDepressionYears()).isFalse();
        assertThat(input.gateOnAdaptiveRules()).isFalse();
    }
}
