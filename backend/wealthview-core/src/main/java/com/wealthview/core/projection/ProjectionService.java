package com.wealthview.core.projection;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wealthview.core.common.Entities;
import com.wealthview.core.projection.dto.CompareRequest;
import com.wealthview.core.projection.dto.CompareResponse;
import com.wealthview.core.projection.dto.ProjectionResultResponse;
import com.wealthview.core.projection.dto.ProjectionRunResult;
import com.wealthview.core.projection.dto.ScenarioParams;
import com.wealthview.core.projection.tax.StateTaxCalculatorFactory;
import com.wealthview.persistence.entity.ProjectionScenarioEntity;
import com.wealthview.persistence.repository.ProjectionScenarioRepository;
import tools.jackson.databind.ObjectMapper;

@Service
public class ProjectionService {

    private static final Logger log = LoggerFactory.getLogger(ProjectionService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ProjectionScenarioRepository scenarioRepository;
    private final ProjectionEngine projectionEngine;
    private final ProjectionInputBuilder projectionInputBuilder;
    private final StateTaxCalculatorFactory stateTaxCalculatorFactory;

    public ProjectionService(ProjectionScenarioRepository scenarioRepository,
                             ProjectionEngine projectionEngine,
                             ProjectionInputBuilder projectionInputBuilder,
                             StateTaxCalculatorFactory stateTaxCalculatorFactory) {
        this.scenarioRepository = scenarioRepository;
        this.projectionEngine = projectionEngine;
        this.projectionInputBuilder = projectionInputBuilder;
        this.stateTaxCalculatorFactory = stateTaxCalculatorFactory;
    }

    @Transactional(readOnly = true)
    public CompareResponse compareScenarios(UUID tenantId, CompareRequest request) {
        log.info("Comparing {} scenarios for tenant {}", request.scenarioIds().size(), tenantId);
        var results = new ArrayList<ProjectionResultResponse>();
        for (var scenarioId : request.scenarioIds()) {
            var scenario = scenarioRepository.findByTenant_IdAndId(tenantId, scenarioId)
                    .orElseThrow(Entities.notFound("Scenario", scenarioId));
            results.add(projectionEngine.run(projectionInputBuilder.build(scenario, tenantId)));
        }
        return new CompareResponse(results);
    }

    @Transactional(readOnly = true)
    public ProjectionRunResult runProjection(UUID tenantId, UUID scenarioId) {
        log.info("Running projection for scenario {} tenant {}", scenarioId, tenantId);
        var scenario = scenarioRepository.findByTenant_IdAndId(tenantId, scenarioId)
                .orElseThrow(Entities.notFound("Scenario"));
        var inputResult = projectionInputBuilder.buildWithMetadata(scenario, tenantId);
        var result = projectionEngine.run(inputResult.input());
        return new ProjectionRunResult(result, inputResult.unclassifiedSymbols(), resolveWarnings(scenario));
    }

    /**
     * Run-level warnings not reflected in the byte-pinned {@link ProjectionResultResponse} (audit
     * C3): today, just the scenario's filing state having no modeled {@code StateTaxCalculator}. The
     * engine itself already logs this once per run (see {@code StateTaxCalculatorFactory#forState});
     * this re-derives the SAME message (a pure lookup, no extra logging) purely to surface it on the
     * API response.
     */
    private List<String> resolveWarnings(ProjectionScenarioEntity scenario) {
        var params = ScenarioParams.parseOrEmpty(MAPPER, scenario.getParamsJson());
        return stateTaxCalculatorFactory.unsupportedStateWarning(params.state())
                .map(List::of)
                .orElseGet(List::of);
    }
}
