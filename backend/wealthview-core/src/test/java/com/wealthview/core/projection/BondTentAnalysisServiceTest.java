package com.wealthview.core.projection;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.wealthview.core.exception.EntityNotFoundException;
import com.wealthview.core.projection.dto.BondTentReplayOutcome;
import com.wealthview.core.projection.dto.BondTentSimulation;
import com.wealthview.core.projection.dto.GuardrailOptimizationInput;
import com.wealthview.core.projection.dto.HistoricalReturnPath;
import com.wealthview.core.projection.dto.ProjectionInputResult;
import com.wealthview.core.projection.dto.ProjectionInput;
import com.wealthview.core.testutil.ScenarioMother;
import com.wealthview.persistence.entity.ProjectionScenarioEntity;
import com.wealthview.persistence.entity.SpendingProfileEntity;
import com.wealthview.persistence.entity.TenantEntity;
import com.wealthview.persistence.repository.ProjectionScenarioRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BondTentAnalysisServiceTest {

    private static final String TENT_PARAMS = """
            {"birth_year":1968,"bond_tent":{"coverage_years":5,"build_up_start_years":3,
            "mixes":[{"name":"Plan","shares":{"ultrashort":0.5,"short":0,"intermediate":0.5,"long":0}}],
            "yields":{"ultrashort":0.0394,"short":0.0465,"intermediate":0.048,"long":0.0539}}}""";

    @Mock
    private ProjectionScenarioRepository scenarioRepository;
    @Mock
    private ProjectionInputBuilder projectionInputBuilder;
    @Mock
    private ProjectionEngine projectionEngine;
    @Mock
    private CrashReplayPaths crashReplayPaths;
    @Mock
    private BondTentSimulator bondTentSimulator;
    @Mock
    private GuardrailProfileService guardrailProfileService;

    private BondTentAnalysisService service;
    private UUID tenantId;
    private UUID scenarioId;
    private TenantEntity tenant;

    @BeforeEach
    void setUp() {
        service = new BondTentAnalysisService(scenarioRepository, projectionInputBuilder, projectionEngine,
                crashReplayPaths, bondTentSimulator, guardrailProfileService);
        tenantId = UUID.randomUUID();
        scenarioId = UUID.randomUUID();
        tenant = new TenantEntity("Test");
    }

    private ProjectionScenarioEntity planned(String paramsJson, LocalDate retirement) {
        var scenario = new ProjectionScenarioEntity(
                tenant, "Plan", retirement, 90, new BigDecimal("0.03"), paramsJson);
        scenario.setSpendingProfile(
                new SpendingProfileEntity(tenant, "Core", new BigDecimal("40000"), BigDecimal.ZERO, null));
        return scenario;
    }

    private ProjectionInput stubScenario(ProjectionScenarioEntity scenario, LocalDate retirement, int currentYear) {
        when(scenarioRepository.findByTenant_IdAndId(tenantId, scenarioId)).thenReturn(Optional.of(scenario));
        var input = new ProjectionInput(scenarioId, "Plan", retirement, 90, new BigDecimal("0.03"), TENT_PARAMS,
                List.of(), null, currentYear);
        lenient().when(projectionInputBuilder.buildWithMetadata(scenario, tenantId))
                .thenReturn(new ProjectionInputResult(input, List.of()));
        return input;
    }

    private void stubPaths(int expectedCrashFirst, int expectedCrashCalendar, int from, int to,
                           HistoricalReturnPath path) {
        lenient().when(crashReplayPaths.firstYear()).thenReturn(1954);
        lenient().when(crashReplayPaths.lastYear()).thenReturn(2025);
        when(crashReplayPaths.build(expectedCrashFirst, expectedCrashCalendar, from, to)).thenReturn(path);
    }

    @Test
    void replay_namedCrash_anchorsTheFirstYearAtRetirementPlusOffset() {
        var retirement = LocalDate.of(2030, 1, 1);
        var scenario = planned(TENT_PARAMS, retirement);
        var input = stubScenario(scenario, retirement, 2026);
        var path = mock(HistoricalReturnPath.class);
        // birth 1968 + end age 90 = 2058
        stubPaths(2008, 2028, 2026, 2058, path);
        var outcomes = List.<BondTentReplayOutcome>of();
        when(projectionEngine.replayBondTent(input, path)).thenReturn(outcomes);

        var replay = service.replay(tenantId, scenarioId, "financial_crisis_2008", null, -2);

        assertThat(replay.crashLabel()).isEqualTo("2007–09");
        assertThat(replay.crashFirstYear()).isEqualTo(2008);
        assertThat(replay.crashLastYear()).isEqualTo(2009);
        assertThat(replay.crashCalendarYear()).isEqualTo(2028);
        assertThat(replay.offset()).isEqualTo(-2);
        assertThat(replay.retirementYear()).isEqualTo(2030);
        assertThat(replay.plannedYears()).isEqualByComparingTo("5");
        assertThat(replay.checkpointAge()).isEqualTo(70);
        assertThat(replay.availableOffsets()).containsExactly(-3, -2, -1, 0, 1, 2, 3);
        assertThat(replay.firstStartYear()).isEqualTo(1954);
        assertThat(replay.lastStartYear()).isEqualTo(2025);
        assertThat(replay.outcomes()).isSameAs(outcomes);
    }

    @Test
    void replay_startYear_isAOneYearWindow() {
        var retirement = LocalDate.of(2030, 1, 1);
        var scenario = planned(TENT_PARAMS, retirement);
        var input = stubScenario(scenario, retirement, 2026);
        var path = mock(HistoricalReturnPath.class);
        stubPaths(1990, 2030, 2026, 2058, path);
        when(projectionEngine.replayBondTent(input, path)).thenReturn(List.of());

        var replay = service.replay(tenantId, scenarioId, null, 1990, 0);

        assertThat(replay.crashLabel()).isEqualTo("1990");
        assertThat(replay.crashFirstYear()).isEqualTo(1990);
        assertThat(replay.crashLastYear()).isEqualTo(1990);
    }

    @Test
    void replay_retiredAlready_offersOnlyTodayOnward() {
        var retirement = LocalDate.of(2020, 1, 1);
        var scenario = planned(TENT_PARAMS, retirement);
        var input = stubScenario(scenario, retirement, 2026);
        var path = mock(HistoricalReturnPath.class);
        stubPaths(2008, 2026, 2026, 2058, path);
        when(projectionEngine.replayBondTent(input, path)).thenReturn(List.of());

        var replay = service.replay(tenantId, scenarioId, "financial_crisis_2008", null, 0);

        assertThat(replay.availableOffsets()).containsExactly(0, 1, 2, 3);
        assertThat(replay.retirementYear()).isEqualTo(2026);
    }

    @Test
    void replay_offsetBeforeToday_throws() {
        var retirement = LocalDate.of(2020, 1, 1);
        stubScenario(planned(TENT_PARAMS, retirement), retirement, 2026);
        stubPathsRange();

        assertThatThrownBy(() -> service.replay(tenantId, scenarioId, "financial_crisis_2008", null, -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("That timing would place the crash before today");
    }

    @Test
    void replay_neitherOrBoth_throws() {
        var retirement = LocalDate.of(2030, 1, 1);
        stubScenario(planned(TENT_PARAMS, retirement), retirement, 2026);

        assertThatThrownBy(() -> service.replay(tenantId, scenarioId, null, null, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Choose a crash or a start year");
        assertThatThrownBy(() -> service.replay(tenantId, scenarioId, "dot_com_2000", 1990, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Choose a crash or a start year");
    }

    @Test
    void replay_unknownCrash_throws() {
        var retirement = LocalDate.of(2030, 1, 1);
        stubScenario(planned(TENT_PARAMS, retirement), retirement, 2026);

        assertThatThrownBy(() -> service.replay(tenantId, scenarioId, "black_monday", null, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unknown crash: black_monday");
    }

    @Test
    void replay_startYearOutOfRange_throws() {
        var retirement = LocalDate.of(2030, 1, 1);
        stubScenario(planned(TENT_PARAMS, retirement), retirement, 2026);
        stubPathsRange();

        assertThatThrownBy(() -> service.replay(tenantId, scenarioId, null, 1953, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Start year must be between 1954 and 2025");
    }

    @Test
    void replay_offsetOutOfRange_throws() {
        var retirement = LocalDate.of(2030, 1, 1);
        stubScenario(planned(TENT_PARAMS, retirement), retirement, 2026);
        stubPathsRange();

        assertThatThrownBy(() -> service.replay(tenantId, scenarioId, "dot_com_2000", null, 4))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Crash timing must be between 3 years before and 3 years after retirement");
    }

    @Test
    void replay_noSpendingPlan_throws() {
        var scenario = ScenarioMother.scenarioWithParams(tenant, TENT_PARAMS);
        when(scenarioRepository.findByTenant_IdAndId(tenantId, scenarioId)).thenReturn(Optional.of(scenario));

        assertThatThrownBy(() -> service.replay(tenantId, scenarioId, "dot_com_2000", null, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("A bond tent analysis needs a spending plan and a bond tent");
    }

    @Test
    void replay_noTent_throws() {
        var scenario = planned("{\"birth_year\":1968}", LocalDate.of(2030, 1, 1));
        when(scenarioRepository.findByTenant_IdAndId(tenantId, scenarioId)).thenReturn(Optional.of(scenario));

        assertThatThrownBy(() -> service.replay(tenantId, scenarioId, "dot_com_2000", null, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("A bond tent analysis needs a spending plan and a bond tent");
    }

    @Test
    void simulate_noTent_throws() {
        var scenario = planned("{\"birth_year\":1968}", LocalDate.of(2030, 1, 1));
        when(scenarioRepository.findByTenant_IdAndId(tenantId, scenarioId)).thenReturn(Optional.of(scenario));

        assertThatThrownBy(() -> service.simulate(tenantId, scenarioId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("A bond tent analysis needs a spending plan and a bond tent");
    }

    @Test
    void simulate_noRetirementDate_throws() {
        var scenario = planned(TENT_PARAMS, null);
        stubScenario(scenario, null, 2026);

        assertThatThrownBy(() -> service.simulate(tenantId, scenarioId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("A bond tent simulation needs a retirement date");
        verifyNoInteractions(guardrailProfileService, bondTentSimulator);
    }

    @Test
    void simulate_passesTheEvaluationInputToTheSimulator() {
        var retirement = LocalDate.of(2030, 1, 1);
        var scenario = planned(TENT_PARAMS, retirement);
        var input = stubScenario(scenario, retirement, 2026);
        var evaluation = mock(GuardrailOptimizationInput.class);
        when(guardrailProfileService.evaluationInput(scenario, input)).thenReturn(evaluation);
        var simulation = new BondTentSimulation(5000, new BigDecimal("5"), List.of());
        when(bondTentSimulator.simulate(evaluation, input)).thenReturn(simulation);

        var result = service.simulate(tenantId, scenarioId);

        assertThat(result).isSameAs(simulation);
        verify(bondTentSimulator).simulate(evaluation, input);
    }

    @Test
    void replay_otherTenantsScenario_throwsNotFound() {
        when(scenarioRepository.findByTenant_IdAndId(tenantId, scenarioId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.replay(tenantId, scenarioId, "dot_com_2000", null, 0))
                .isInstanceOf(EntityNotFoundException.class);
        assertThatThrownBy(() -> service.simulate(tenantId, scenarioId))
                .isInstanceOf(EntityNotFoundException.class);
    }

    private void stubPathsRange() {
        lenient().when(crashReplayPaths.firstYear()).thenReturn(1954);
        lenient().when(crashReplayPaths.lastYear()).thenReturn(2025);
    }
}
