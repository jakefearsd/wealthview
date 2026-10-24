package com.wealthview.core.projection;

import java.util.List;

import com.wealthview.core.projection.dto.BondTentConfig;
import com.wealthview.core.projection.dto.BondTentReplayOutcome;
import com.wealthview.core.projection.dto.BondTentSizing;
import com.wealthview.core.projection.dto.HistoricalReturnPath;
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

    /**
     * Sizes a bond tent for every mix of {@code config} from a tent-free run of {@code input}. Only the
     * deterministic engine sizes tents; the default rejects the call.
     */
    default BondTentSizing sizeBondTent(ProjectionInput input, BondTentConfig config) {
        throw new UnsupportedOperationException("This projection engine cannot size a bond tent");
    }

    /**
     * Replays {@code input} through the historical {@code path} once without its bond tent and once per tent mix.
     * Only the deterministic engine replays; the default rejects the call.
     */
    default List<BondTentReplayOutcome> replayBondTent(ProjectionInput input, HistoricalReturnPath path) {
        throw new UnsupportedOperationException("This projection engine cannot replay a bond tent");
    }
}
