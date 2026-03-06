package com.wealthview.core.projection;

import com.wealthview.core.projection.dto.ProjectionResultResponse;
import com.wealthview.persistence.entity.ProjectionScenarioEntity;

public interface ProjectionEngine {

    ProjectionResultResponse run(ProjectionScenarioEntity scenario);
}
