package com.wealthview.core.projection.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.wealthview.persistence.entity.GuardrailSpendingProfileEntity;
import com.wealthview.persistence.entity.ProjectionScenarioEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GuardrailProfileResponseTest {

    private GuardrailSpendingProfileEntity baseEntity() {
        var scenario = mock(ProjectionScenarioEntity.class);
        when(scenario.getId()).thenReturn(UUID.randomUUID());

        var entity = mock(GuardrailSpendingProfileEntity.class);
        when(entity.getId()).thenReturn(UUID.randomUUID());
        when(entity.getScenario()).thenReturn(scenario);
        when(entity.getName()).thenReturn("Opt");
        when(entity.getEssentialFloor()).thenReturn(new BigDecimal("50000"));
        when(entity.getTerminalBalanceTarget()).thenReturn(BigDecimal.ZERO);
        when(entity.getReturnMean()).thenReturn(new BigDecimal("0.07"));
        when(entity.getTrialCount()).thenReturn(500);
        when(entity.getConfidenceLevel()).thenReturn(new BigDecimal("0.85"));
        when(entity.getMedianFinalBalance()).thenReturn(new BigDecimal("1000000"));
        when(entity.getFailureRate()).thenReturn(new BigDecimal("0.05"));
        when(entity.getPercentile10Final()).thenReturn(new BigDecimal("500000"));
        when(entity.isStale()).thenReturn(false);
        when(entity.getCreatedAt()).thenReturn(OffsetDateTime.now().minusHours(1));
        when(entity.getUpdatedAt()).thenReturn(OffsetDateTime.now().minusHours(1));
        when(entity.getPortfolioFloor()).thenReturn(BigDecimal.ZERO);
        when(entity.getMaxAnnualAdjustmentRate()).thenReturn(null);
        when(entity.getPhaseBlendYears()).thenReturn(2);
        when(entity.getRiskTolerance()).thenReturn("moderate");
        when(entity.getCashReserveYears()).thenReturn(2);
        when(entity.getCashReturnRate()).thenReturn(new BigDecimal("0.04"));
        return entity;
    }

    @Test
    void from_withValidJsonFields_mapsAllFields() {
        var entity = baseEntity();
        when(entity.getPhases()).thenReturn("""
                [{"name":"Go-Go","startAge":62,"endAge":70,"priorityWeight":2,"targetSpending":80000}]
                """);
        when(entity.getYearlySpending()).thenReturn("""
                [{"year":2035,"age":62,"recommended":80000,"corridorLow":70000,"corridorHigh":90000,
                  "essentialFloor":50000,"discretionary":30000,"incomeOffset":15000,
                  "portfolioWithdrawal":65000,"phaseName":"Go-Go"}]
                """);
        when(entity.getConversionSchedule()).thenReturn(null);

        var response = GuardrailProfileResponse.from(entity);

        assertThat(response.name()).isEqualTo("Opt");
        assertThat(response.phases()).hasSize(1);
        assertThat(response.phases().getFirst().name()).isEqualTo("Go-Go");
        assertThat(response.yearlySpending()).hasSize(1);
        assertThat(response.conversionSchedule()).isNull();
        assertThat(response.stale()).isFalse();
    }

    @Test
    void from_withMalformedJson_returnsEmptyLists() {
        var entity = baseEntity();
        when(entity.getPhases()).thenReturn("[not json");
        when(entity.getYearlySpending()).thenReturn("[not json");
        when(entity.getConversionSchedule()).thenReturn(null);

        var response = GuardrailProfileResponse.from(entity);

        assertThat(response.phases()).isEmpty();
        assertThat(response.yearlySpending()).isEmpty();
    }

    @Test
    void from_withConversionScheduleJson_populatesSchedule() {
        var entity = baseEntity();
        when(entity.getPhases()).thenReturn("[]");
        when(entity.getYearlySpending()).thenReturn("[]");
        when(entity.getConversionSchedule()).thenReturn("""
                {
                  "years": [],
                  "lifetimeTaxWithConversions": 100000,
                  "lifetimeTaxWithout": 150000,
                  "taxSavings": 50000,
                  "conversionBracketRate": 0.22,
                  "rmdTargetBracketRate": 0.12,
                  "rmdBracketHeadroom": 0.10,
                  "exhaustionAge": 75,
                  "exhaustionTargetMet": true,
                  "traditionalExhaustionBuffer": 5,
                  "mcExhaustionPct": null,
                  "targetTraditionalBalance": null
                }
                """);

        var response = GuardrailProfileResponse.from(entity);

        assertThat(response.conversionSchedule()).isNotNull();
        assertThat(response.conversionSchedule().taxSavings()).isEqualByComparingTo("50000");
    }

    @Test
    void from_withBlankConversionSchedule_leavesScheduleNull() {
        var entity = baseEntity();
        when(entity.getPhases()).thenReturn("[]");
        when(entity.getYearlySpending()).thenReturn("[]");
        when(entity.getConversionSchedule()).thenReturn("   ");

        var response = GuardrailProfileResponse.from(entity);

        assertThat(response.conversionSchedule()).isNull();
    }

    @Test
    void from_withMalformedConversionSchedule_leavesScheduleNull() {
        var entity = baseEntity();
        when(entity.getPhases()).thenReturn("[]");
        when(entity.getYearlySpending()).thenReturn("[]");
        when(entity.getConversionSchedule()).thenReturn("{not valid");

        var response = GuardrailProfileResponse.from(entity);

        assertThat(response.conversionSchedule()).isNull();
    }

    @Test
    void from_withUpdatedAtOlderThan24Hours_marksResponseStale() {
        var entity = baseEntity();
        when(entity.isStale()).thenReturn(false);
        when(entity.getUpdatedAt()).thenReturn(OffsetDateTime.now().minusHours(48));
        when(entity.getPhases()).thenReturn("[]");
        when(entity.getYearlySpending()).thenReturn("[]");
        when(entity.getConversionSchedule()).thenReturn(null);

        var response = GuardrailProfileResponse.from(entity);

        assertThat(response.stale()).isTrue();
    }

    @Test
    void from_singleArgOverload_leavesFixedReturnShareNull() {
        var entity = baseEntity();
        when(entity.getPhases()).thenReturn("[]");
        when(entity.getYearlySpending()).thenReturn("[]");
        when(entity.getConversionSchedule()).thenReturn(null);

        var response = GuardrailProfileResponse.from(entity);

        assertThat(response.fixedReturnShare()).isNull();
    }

    @Test
    void from_withFixedReturnShare_attachesItToResponse() {
        var entity = baseEntity();
        when(entity.getPhases()).thenReturn("[]");
        when(entity.getYearlySpending()).thenReturn("[]");
        when(entity.getConversionSchedule()).thenReturn(null);

        var response = GuardrailProfileResponse.from(entity, new BigDecimal("0.6000"));

        assertThat(response.fixedReturnShare()).isEqualByComparingTo("0.6000");
    }

    @Test
    void isOlderThan24Hours_withRecentUpdate_returnsFalse() {
        var entity = mock(GuardrailSpendingProfileEntity.class);
        when(entity.getUpdatedAt()).thenReturn(OffsetDateTime.now().minusHours(3));

        assertThat(GuardrailProfileResponse.isOlderThan24Hours(entity)).isFalse();
    }

    @Test
    void isOlderThan24Hours_withNullUpdate_returnsFalse() {
        var entity = mock(GuardrailSpendingProfileEntity.class);
        when(entity.getUpdatedAt()).thenReturn(null);

        assertThat(GuardrailProfileResponse.isOlderThan24Hours(entity)).isFalse();
    }
}
