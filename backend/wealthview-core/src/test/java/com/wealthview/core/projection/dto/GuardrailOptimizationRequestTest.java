package com.wealthview.core.projection.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GuardrailOptimizationRequestTest {

    /**
     * Every component gets a value unique within its own type, so a transposed pair of same-typed
     * builder fields (the failure mode a 21-component positional constructor invites) fails an
     * assertion instead of compiling silently.
     */
    @Test
    void builder_everyComponentSet_roundTripsEachValueToItsOwnAccessor() {
        var scenarioId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        var phases = List.of(new GuardrailPhaseInput("go-go", 60, 70, 1));

        var request = GuardrailOptimizationRequest.builder()
                .scenarioId(scenarioId)
                .name("profile-name")
                .essentialFloor(new BigDecimal("1.01"))
                .terminalBalanceTarget(new BigDecimal("2.02"))
                .returnMean(new BigDecimal("3.03"))
                .trialCount(1101)
                .confidenceLevel(new BigDecimal("0.9404"))
                .phases(phases)
                .portfolioFloor(new BigDecimal("5.05"))
                .maxAnnualAdjustmentRate(new BigDecimal("6.06"))
                .phaseBlendYears(1102)
                .riskTolerance("risk-tolerance")
                .cashReserveYears(1103)
                .cashReturnRate(new BigDecimal("7.07"))
                .optimizeConversions(true)
                .conversionBracketRate(new BigDecimal("8.08"))
                .rmdTargetBracketRate(new BigDecimal("9.09"))
                .traditionalExhaustionBuffer(1104)
                .rmdBracketHeadroom(new BigDecimal("10.10"))
                .dynamicSequencingBracketRate(new BigDecimal("11.11"))
                .gateOnAdaptiveRules(false)
                .build();

        assertThat(request.scenarioId()).isEqualTo(scenarioId);
        assertThat(request.name()).isEqualTo("profile-name");
        assertThat(request.essentialFloor()).isEqualByComparingTo("1.01");
        assertThat(request.terminalBalanceTarget()).isEqualByComparingTo("2.02");
        assertThat(request.returnMean()).isEqualByComparingTo("3.03");
        assertThat(request.trialCount()).isEqualTo(1101);
        assertThat(request.confidenceLevel()).isEqualByComparingTo("0.9404");
        assertThat(request.phases()).isSameAs(phases);
        assertThat(request.portfolioFloor()).isEqualByComparingTo("5.05");
        assertThat(request.maxAnnualAdjustmentRate()).isEqualByComparingTo("6.06");
        assertThat(request.phaseBlendYears()).isEqualTo(1102);
        assertThat(request.riskTolerance()).isEqualTo("risk-tolerance");
        assertThat(request.cashReserveYears()).isEqualTo(1103);
        assertThat(request.cashReturnRate()).isEqualByComparingTo("7.07");
        assertThat(request.optimizeConversions()).isTrue();
        assertThat(request.conversionBracketRate()).isEqualByComparingTo("8.08");
        assertThat(request.rmdTargetBracketRate()).isEqualByComparingTo("9.09");
        assertThat(request.traditionalExhaustionBuffer()).isEqualTo(1104);
        assertThat(request.rmdBracketHeadroom()).isEqualByComparingTo("10.10");
        assertThat(request.dynamicSequencingBracketRate()).isEqualByComparingTo("11.11");
        assertThat(request.gateOnAdaptiveRules()).isFalse();
    }

    @Test
    void builder_nothingSet_leavesEveryComponentUnspecified() {
        var request = GuardrailOptimizationRequest.builder().build();

        assertThat(request.scenarioId()).isNull();
        assertThat(request.trialCount()).isNull();
        assertThat(request.phases()).isNull();
        assertThat(request.optimizeConversions()).isNull();
        assertThat(request.gateOnAdaptiveRules()).isNull();
    }
}
