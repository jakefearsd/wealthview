package com.wealthview.core.projection;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wealthview.core.common.Entities;
import com.wealthview.core.projection.dto.BondTentConfig;
import com.wealthview.core.projection.dto.BondTentReplay;
import com.wealthview.core.projection.dto.BondTentSimulation;
import com.wealthview.core.projection.dto.HistoricalCrash;
import com.wealthview.core.projection.dto.ProjectionInput;
import com.wealthview.core.projection.dto.ScenarioParams;
import com.wealthview.persistence.entity.ProjectionScenarioEntity;
import com.wealthview.persistence.repository.ProjectionScenarioRepository;
import tools.jackson.databind.ObjectMapper;

/**
 * The bond tent tab's two analyses: a scenario replayed through a historical crash, and its spending plan held fixed
 * over Monte Carlo paths. Both are read-only and need a spending plan and a bond tent.
 */
@Service
public class BondTentAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(BondTentAnalysisService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final int MAX_OFFSET = 3;
    private static final int CHECKPOINT_AGE = 70;
    private static final int DEFAULT_END_AGE = 90;
    private static final int DEFAULT_YEARS_TO_RETIREMENT = 30;
    private static final int DEFAULT_AGE = 35;

    private final ProjectionScenarioRepository scenarioRepository;
    private final ProjectionInputBuilder projectionInputBuilder;
    private final ProjectionEngine projectionEngine;
    private final CrashReplayPaths crashReplayPaths;
    private final BondTentSimulator bondTentSimulator;
    private final GuardrailProfileService guardrailProfileService;

    public BondTentAnalysisService(ProjectionScenarioRepository scenarioRepository,
                                   ProjectionInputBuilder projectionInputBuilder,
                                   ProjectionEngine projectionEngine,
                                   CrashReplayPaths crashReplayPaths,
                                   BondTentSimulator bondTentSimulator,
                                   GuardrailProfileService guardrailProfileService) {
        this.scenarioRepository = scenarioRepository;
        this.projectionInputBuilder = projectionInputBuilder;
        this.projectionEngine = projectionEngine;
        this.crashReplayPaths = crashReplayPaths;
        this.bondTentSimulator = bondTentSimulator;
        this.guardrailProfileService = guardrailProfileService;
    }

    @Transactional(readOnly = true)
    public BondTentSimulation simulate(UUID tenantId, UUID scenarioId) {
        log.debug("Simulating bond tent for scenario {} tenant {}", scenarioId, tenantId);
        var scenario = loadAnalysable(tenantId, scenarioId).scenario();
        var projectionInput = projectionInputBuilder.buildWithMetadata(scenario, tenantId).input();
        var evaluation = guardrailProfileService.evaluationInput(scenario, projectionInput);
        return bondTentSimulator.simulate(evaluation, projectionInput);
    }

    /**
     * Replays the scenario through a named crash ({@code crash}) or a one-year window starting at {@code startYear}
     * (exactly one of the two). The crash's first year lands {@code offset} years from the anchor: retirement, or
     * today when the scenario is already retired.
     */
    @Transactional(readOnly = true)
    public BondTentReplay replay(UUID tenantId, UUID scenarioId, @Nullable String crash,
                                 @Nullable Integer startYear, int offset) {
        log.debug("Replaying bond tent for scenario {} tenant {}", scenarioId, tenantId);
        var analysable = loadAnalysable(tenantId, scenarioId);
        var window = resolveWindow(crash, startYear);
        if (Math.abs(offset) > MAX_OFFSET) {
            throw new IllegalArgumentException(
                    "Crash timing must be between 3 years before and 3 years after retirement");
        }
        var input = projectionInputBuilder.buildWithMetadata(analysable.scenario(), tenantId).input();
        var years = Years.resolve(input, analysable.params());
        if (years.anchor() + offset < years.currentYear()) {
            throw new IllegalArgumentException("That timing would place the crash before today");
        }
        int crashCalendarYear = years.anchor() + offset;
        var path = crashReplayPaths.build(window.firstYear(), crashCalendarYear, years.currentYear(), years.endYear());
        var outcomes = projectionEngine.replayBondTent(input, path);
        return new BondTentReplay(window.label(), window.firstYear(), window.lastYear(), crashCalendarYear, offset,
                years.anchor(), analysable.tent().coverageYears(), CHECKPOINT_AGE, years.availableOffsets(),
                crashReplayPaths.firstYear(), crashReplayPaths.lastYear(), outcomes);
    }

    private Analysable loadAnalysable(UUID tenantId, UUID scenarioId) {
        var scenario = scenarioRepository.findByTenant_IdAndId(tenantId, scenarioId)
                .orElseThrow(Entities.notFound("Scenario", scenarioId));
        var params = ScenarioParams.parseOrEmpty(MAPPER, scenario.getParamsJson());
        boolean hasPlan = scenario.getSpendingProfile() != null || scenario.getGuardrailProfile() != null;
        var tent = params.bondTent();
        if (!hasPlan || tent == null) {
            throw new IllegalArgumentException("A bond tent analysis needs a spending plan and a bond tent");
        }
        return new Analysable(scenario, params, tent);
    }

    /** A tenant's scenario that has a spending plan and a bond tent, with its parsed parameters. */
    private record Analysable(ProjectionScenarioEntity scenario, ScenarioParams params, BondTentConfig tent) {
    }

    private CrashWindow resolveWindow(@Nullable String crash, @Nullable Integer startYear) {
        if (crash == null == (startYear == null)) {
            throw new IllegalArgumentException("Choose a crash or a start year");
        }
        if (crash != null) {
            var named = HistoricalCrash.fromKey(crash)
                    .orElseThrow(() -> new IllegalArgumentException("Unknown crash: " + crash));
            return new CrashWindow(named.label(), named.firstYear(), named.lastYear());
        }
        int first = crashReplayPaths.firstYear();
        int last = crashReplayPaths.lastYear();
        if (startYear < first || startYear > last) {
            throw new IllegalArgumentException("Start year must be between " + first + " and " + last);
        }
        return new CrashWindow(String.valueOf(startYear), startYear, startYear);
    }

    private record CrashWindow(String label, int firstYear, int lastYear) {
    }

    /** The calendar years a replay is laid out on, resolved as the deterministic engine resolves them. */
    private record Years(int currentYear, int anchor, int endYear) {

        static Years resolve(ProjectionInput input, ScenarioParams params) {
            int currentYear = input.referenceYear() != null ? input.referenceYear() : LocalDate.now().getYear();
            int birthYear = params.birthYear() != null ? params.birthYear() : currentYear - DEFAULT_AGE;
            int retirementYear = input.retirementDate() != null
                    ? input.retirementDate().getYear() : currentYear + DEFAULT_YEARS_TO_RETIREMENT;
            int endAge = input.endAge() != null ? input.endAge() : DEFAULT_END_AGE;
            return new Years(currentYear, Math.max(retirementYear, currentYear), birthYear + endAge);
        }

        List<Integer> availableOffsets() {
            return IntStream.rangeClosed(-MAX_OFFSET, MAX_OFFSET)
                    .filter(o -> anchor + o >= currentYear).boxed().toList();
        }
    }
}
