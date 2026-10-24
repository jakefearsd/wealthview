package com.wealthview.projection;

import com.wealthview.core.projection.dto.PoolType;

/**
 * Replays the bond tent's build-up for one trial and one candidate. The pool totals before and after each
 * pre-retirement year's growth come from {@link TentPaths}; the replay carries a per-pool difference
 * ({@code D}) between the tent-adjusted pools and the recorded no-tent pools, so the trial's retirement
 * start balances can be corrected for the tent's different returns.
 */
final class TentBuildUp {

    /** The tent at retirement plus the per-pool corrections to the no-tent start balances. */
    record Start(TrialTent tent, double taxableAdjustment, double traditionalAdjustment, double rothAdjustment) {
    }

    private TentBuildUp() {
    }

    /**
     * Follows the plan's line from the build-up start. Below the previous high a year adds at most the year's
     * contributions; at retirement the full {@code target} is added only when the build-up is zero years or the
     * index is at its high.
     */
    static Start toRetirement(TrialTentPlan plan, TentPaths paths, int trial, double target,
                              TrialStartBalances noTentStart) {
        var tent = new TrialTent(plan, paths.sleeveReturnsOf(trial));
        int pre = plan.yearsBeforeRetirement();
        double diffTaxable = 0;
        double diffTraditional = 0;
        double diffRoth = 0;
        for (int y = 0; y < pre; y++) {
            double beforeTaxable = paths.poolBeforeGrowth(trial, y, PoolType.TAXABLE);
            double beforeTraditional = paths.poolBeforeGrowth(trial, y, PoolType.TRADITIONAL);
            double beforeRoth = paths.poolBeforeGrowth(trial, y, PoolType.ROTH);
            double afterTaxable = paths.poolAfterGrowth(trial, y, PoolType.TAXABLE);
            double afterTraditional = paths.poolAfterGrowth(trial, y, PoolType.TRADITIONAL);
            double afterRoth = paths.poolAfterGrowth(trial, y, PoolType.ROTH);
            double levelTaxable = beforeTaxable + diffTaxable;
            double levelTraditional = beforeTraditional + diffTraditional;
            double levelRoth = beforeRoth + diffRoth;
            if (y >= plan.buildUpStartIndex()) {
                tent.clampAll(levelTaxable, levelTraditional, levelRoth);
                double cap = tent.isBelowPeak() ? plan.contributionCap() : Double.POSITIVE_INFINITY;
                tent.topUp(plan.lineLevel(y, target), levelTaxable, levelTraditional, levelRoth, cap);
            }
            double retTaxable = returnOf(beforeTaxable, afterTaxable);
            double retTraditional = returnOf(beforeTraditional, afterTraditional);
            double retRoth = returnOf(beforeRoth, afterRoth);
            double growth = tent.growthReturn(levelTaxable, levelTraditional, levelRoth,
                    retTaxable, retTraditional, retRoth);
            diffTaxable = tent.grownBalance(PoolType.TAXABLE, levelTaxable, retTaxable, y) - afterTaxable;
            diffTraditional = tent.grownBalance(PoolType.TRADITIONAL, levelTraditional, retTraditional, y)
                    - afterTraditional;
            diffRoth = tent.grownBalance(PoolType.ROTH, levelRoth, retRoth, y) - afterRoth;
            tent.growSleeves(y);
            tent.advanceIndex(growth);
        }
        double taxable = noTentStart.taxable() + diffTaxable;
        double traditional = noTentStart.traditional() + diffTraditional;
        double roth = noTentStart.roth() + diffRoth;
        tent.clampAll(taxable, traditional, roth);
        double cap = plan.buildUpSpan() == 0 || !tent.isBelowPeak() ? Double.POSITIVE_INFINITY : 0;
        tent.topUp(target, taxable, traditional, roth, cap);
        return new Start(tent, diffTaxable, diffTraditional, diffRoth);
    }

    /** An ungated top-up to {@code target} over the given balances, with no start-balance adjustment. */
    static Start fundedAtStart(TrialTentPlan plan, double[] sleeveReturns, double target, double taxable,
                               double traditional, double roth) {
        var tent = new TrialTent(plan, sleeveReturns);
        tent.topUp(target, taxable, traditional, roth, Double.POSITIVE_INFINITY);
        return new Start(tent, 0, 0, 0);
    }

    private static double returnOf(double before, double after) {
        return before > 0 ? after / before - 1 : 0;
    }
}
