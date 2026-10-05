package com.wealthview.core.projection;

import java.util.List;

import com.wealthview.core.projection.dto.ProjectionInput;
import com.wealthview.core.projection.dto.ProjectionResultResponse;
import com.wealthview.core.projection.dto.ProjectionRunDetail;

@FunctionalInterface
public interface ProjectionEngine {

    ProjectionResultResponse run(ProjectionInput input);

    /**
     * Phase 1a: the run result plus golden-safe side-channel data (tax pictures, tax space, after-tax
     * legacy). The default wraps {@link #run} with empty side data; the deterministic engine overrides it.
     */
    default ProjectionRunDetail runDetailed(ProjectionInput input) {
        return new ProjectionRunDetail(run(input), List.of(), List.of(), null);
    }
}
