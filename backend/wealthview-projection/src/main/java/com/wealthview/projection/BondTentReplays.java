package com.wealthview.projection;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import org.springframework.lang.Nullable;

import com.wealthview.core.projection.BondTentAssumptions;
import com.wealthview.core.projection.dto.BondTentConfig;
import com.wealthview.core.projection.dto.BondTentReplayOutcome;
import com.wealthview.core.projection.dto.HistoricalReturnPath;
import com.wealthview.core.projection.dto.ProjectionAccountInput;
import com.wealthview.core.projection.dto.ProjectionRunDetail;

/**
 * Orchestrates a crash replay: a tent-free pass on constant returns sizes every mix, then one replay pass without
 * the tent and one per mix, in config order. Each pass builds fresh pools and a fresh {@link CrashReplayer}, so a
 * replay leaves no state behind.
 */
final class BondTentReplays {

    private static final String NO_TENT = "No tent";

    private BondTentReplays() {
    }

    /** Runs one full projection with the given tent and replayer; both are {@code null} for the sizing pass. */
    @FunctionalInterface
    interface ReplayPass {
        ProjectionRunDetail run(@Nullable BondTentPlanner.TentRun tent, @Nullable CrashReplayer replayer);
    }

    /** What is replayed: the scenario's accounts and birth year, and the history it is replayed through. */
    record Subject(List<ProjectionAccountInput> accounts, int birthYear, HistoricalReturnPath path) {
    }

    static List<BondTentReplayOutcome> replay(ReplayPass pass, BondTentConfig config,
                                              @Nullable BondTentAssumptions assumptions, BondTentPlanner.Frame frame,
                                              Subject subject) {
        var realReturns = BondTentPlanner.requireAssumptions(assumptions).realReturns();
        var passOne = pass.run(null, null);
        var sized = BondTentPlanner.size(passOne.result().yearlyData(), config, frame.inflationRate());

        var outcomes = new ArrayList<BondTentReplayOutcome>();
        outcomes.add(replayPass(pass, NO_TENT, null, null, sized, frame, subject));
        for (int i = 0; i < config.mixes().size(); i++) {
            var target = sized.sizing().mixes().get(i).target();
            var buildUp = BondTentPlanner.BuildUp.starting(frame.currentYear(), frame.retirementYear(),
                    config.buildUpStartYears(), target);
            var tent = BondTentPlanner.runFor(sized, config, i, buildUp, realReturns, frame.feeRate(),
                    frame.order());
            outcomes.add(replayPass(pass, config.mixes().get(i).name(), target, tent, sized, frame, subject));
        }
        return List.copyOf(outcomes);
    }

    private static BondTentReplayOutcome replayPass(ReplayPass pass, String name,
                                                    @Nullable BigDecimal target,
                                                    @Nullable BondTentPlanner.TentRun tent,
                                                    BondTentPlanner.Sized sized, BondTentPlanner.Frame frame,
                                                    Subject subject) {
        var replayer = new CrashReplayer(subject.path(), subject.accounts(), frame.inflationRate(),
                frame.feeRate());
        pass.run(tent, replayer);
        return ReplayOutcomes.of(name, target, replayer.years(), sized.sizing(), subject.birthYear());
    }
}
