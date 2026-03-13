package com.wealthview.core.projection;

import com.wealthview.core.projection.dto.ProjectionInput;
import com.wealthview.core.projection.dto.ProjectionResultResponse;

@FunctionalInterface
public interface ProjectionEngine {

    ProjectionResultResponse run(ProjectionInput input);
}
