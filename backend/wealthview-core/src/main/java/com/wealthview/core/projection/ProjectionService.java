package com.wealthview.core.projection;

import com.wealthview.core.common.Entities;
import com.wealthview.core.projection.dto.CompareRequest;
import com.wealthview.core.projection.dto.CompareResponse;
import com.wealthview.core.projection.dto.ProjectionResultResponse;
import com.wealthview.persistence.repository.ProjectionScenarioRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.UUID;

@Service
public class ProjectionService {

    private static final Logger log = LoggerFactory.getLogger(ProjectionService.class);

    private final ProjectionScenarioRepository scenarioRepository;
    private final ProjectionEngine projectionEngine;
    private final ProjectionInputBuilder projectionInputBuilder;

    public ProjectionService(ProjectionScenarioRepository scenarioRepository,
                             ProjectionEngine projectionEngine,
                             ProjectionInputBuilder projectionInputBuilder) {
        this.scenarioRepository = scenarioRepository;
        this.projectionEngine = projectionEngine;
        this.projectionInputBuilder = projectionInputBuilder;
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
    public ProjectionResultResponse runProjection(UUID tenantId, UUID scenarioId) {
        log.info("Running projection for scenario {} tenant {}", scenarioId, tenantId);
        var scenario = scenarioRepository.findByTenant_IdAndId(tenantId, scenarioId)
                .orElseThrow(Entities.notFound("Scenario"));
        return projectionEngine.run(projectionInputBuilder.build(scenario, tenantId));
    }
}
