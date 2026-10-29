package com.wealthview.projection;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import org.springframework.lang.Nullable;

import com.wealthview.core.projection.BondTentAssumptions;
import com.wealthview.core.projection.dto.BondMaturity;
import com.wealthview.core.projection.dto.BondMaturityValues;
import com.wealthview.core.projection.dto.BondTentConfig;
import com.wealthview.core.projection.dto.BondTentCoveredYear;
import com.wealthview.core.projection.dto.BondTentMixSizing;
import com.wealthview.core.projection.dto.BondTentResult;
import com.wealthview.core.projection.dto.BondTentSizing;
import com.wealthview.core.projection.dto.BondTentYear;
import com.wealthview.core.projection.dto.ProjectionRunDetail;
import com.wealthview.core.projection.dto.ProjectionYearDto;
import com.wealthview.core.projection.dto.ScenarioParams;
import com.wealthview.core.projection.dto.SpendingPlan;
import com.wealthview.core.projection.strategy.WithdrawalOrder;

/**
 * The deterministic engine's bond tent mechanics outside the year loop: sizing every mix from the
 * tent-free first pass, the straight build-up line, and the pool configuration for the second pass.
 */
final class BondTentPlanner {

    private static final int MONEY_SCALE = 2;
    private static final int RATE_SCALE = 6;

    private BondTentPlanner() {
    }

    /**
     * The tent to run, if any: a configured tent is active only when the run resolves a spending plan, since
     * the plan is what the first pass withdraws and the tent is sized from.
     */
    static Optional<BondTentConfig> activeConfig(ScenarioParams params, @Nullable SpendingPlan spendingPlan) {
        return spendingPlan == null ? Optional.empty() : Optional.ofNullable(params.bondTent());
    }

    /** What the first pass sized: the full sizing plus the plan mix's target the second pass builds. */
    record Sized(BondTentSizing sizing, BigDecimal planTarget) {
    }

    /**
     * Sizes every mix from a tent-free pass. A retired year's withdrawal is its spending net of income plus the
     * tax the portfolio pays; coverage counts from the first retired year, or from the first projected year when
     * retirement is already past.
     */
    static Sized size(List<ProjectionYearDto> passOne, BondTentConfig config, BigDecimal inflationRate) {
        var retired = passOne.stream().filter(ProjectionYearDto::retired).toList();
        int n = retired.size();
        var spending = new double[n];
        var income = new double[n];
        var tax = new double[n];
        for (int k = 0; k < n; k++) {
            var row = retired.get(k);
            spending[k] = orZero(row.essentialExpenses()).add(orZero(row.discretionaryExpenses())).doubleValue();
            income[k] = orZero(row.incomeStreamsTotal()).doubleValue();
            tax[k] = orZero(row.taxLiability()).doubleValue();
        }
        BigDecimal portfolio = retired.isEmpty()
                ? passOne.isEmpty() ? BigDecimal.ZERO : passOne.getFirst().startBalance()
                : retired.getFirst().startBalance();
        double coverage = config.coverageYears().doubleValue();

        var mixes = new ArrayList<BondTentMixSizing>();
        var covered = new ArrayList<BondTentCoveredYear>();
        for (var mix : config.mixes()) {
            double realYield = BondTentSizer.mixRealYield(config.yields(), mix.shares(), inflationRate.doubleValue());
            var s = BondTentSizer.size(spending, income, tax, coverage, realYield, portfolio.doubleValue());
            mixes.add(mixSizing(mix.name(), realYield, s, portfolio));
            if (covered.isEmpty()) {
                for (int k = 0; k < s.withdrawals().length; k++) {
                    covered.add(new BondTentCoveredYear(retired.get(k).year(), money(s.withdrawals()[k]),
                            rate(s.fractions()[k])));
                }
            }
        }
        var sizing = new BondTentSizing(portfolio.setScale(MONEY_SCALE, RoundingMode.HALF_UP),
                config.coverageYears(), List.copyOf(mixes), List.copyOf(covered));
        return new Sized(sizing, mixes.getFirst().target());
    }

    private static BondTentMixSizing mixSizing(String name, double realYield, BondTentSizer.Sizing s,
                                               BigDecimal portfolio) {
        BigDecimal target = money(s.target());
        BigDecimal share = portfolio.signum() == 0
                ? BigDecimal.ZERO : target.divide(portfolio, RATE_SCALE, RoundingMode.HALF_UP);
        return new BondTentMixSizing(name, rate(realYield), target, share, money(s.ruleOfThumb()), s.reason(),
                money(s.reasonAmount()));
    }

    /**
     * The straight build-up line. {@code start = max(currentYear, retirementYear - buildUpStartYears)}; the
     * reserve rises from zero at {@code start} to {@code target} at retirement, and is held from then on.
     */
    record BuildUp(int currentYear, int retirementYear, int start, BigDecimal target) {

        static BuildUp starting(int currentYear, int retirementYear, int buildUpStartYears, BigDecimal target) {
            int start = Math.max(currentYear, retirementYear - buildUpStartYears);
            return new BuildUp(currentYear, retirementYear, start, target);
        }

        /** The reserve the line asks for at the start of {@code year}, or {@code null} outside the window. */
        @Nullable
        BigDecimal targetFor(int year) {
            if (year < start || year > Math.max(retirementYear, currentYear)) {
                return null;
            }
            int span = retirementYear - start;
            if (span <= 0 || year >= retirementYear) {
                return target;
            }
            return target.multiply(BigDecimal.valueOf(year - start))
                    .divide(BigDecimal.valueOf(span), MONEY_SCALE, RoundingMode.HALF_UP);
        }
    }

    /** Everything the second pass needs to build and report the tent. */
    record TentRun(BondTentSizing sizing, BuildUp buildUp, PoolStrategy.TentSetup setup) {
    }

    /** The run-level facts the two passes share. */
    record Frame(int currentYear, int retirementYear, BigDecimal inflationRate, BigDecimal feeRate,
                 WithdrawalOrder order) {
    }

    /**
     * Runs the tent projection: pass one without the tent produces the withdrawals, the plan mix is sized
     * from them, and pass two builds the tent along the line and holds it. {@code pass} runs one full
     * projection, with the given tent ({@code null} for pass one).
     */
    static ProjectionRunDetail twoPass(Function<TentRun, ProjectionRunDetail> pass,
                                       BondTentConfig config, @Nullable BondTentAssumptions assumptions,
                                       Frame frame) {
        var returns = requireAssumptions(assumptions).realReturns();
        var passOne = pass.apply(null);
        var sized = size(passOne.result().yearlyData(), config, frame.inflationRate());
        var buildUp = BuildUp.starting(frame.currentYear(), frame.retirementYear(), config.buildUpStartYears(),
                sized.planTarget());
        return pass.apply(runFor(sized, config, 0, buildUp, returns, frame.feeRate(), frame.order()));
    }

    /** The bond return assumptions a tent run needs; a run that configures a tent without them is a wiring error. */
    static BondTentAssumptions requireAssumptions(@Nullable BondTentAssumptions assumptions) {
        if (assumptions == null) {
            throw new IllegalStateException("A bond tent is configured but bond return assumptions are not wired");
        }
        return assumptions;
    }

    /**
     * The tent for mix {@code mixIndex}: its shares, built along {@code buildUp}'s line to the line's own target,
     * so the caller passes a line carrying the mix's target. {@code twoPass} runs the plan mix (index 0); a replay
     * runs every mix.
     */
    static TentRun runFor(Sized sized, BondTentConfig config, int mixIndex, BuildUp buildUp,
                          Map<BondMaturity, Double> realReturns, BigDecimal feeRate, WithdrawalOrder order) {
        var net = new EnumMap<BondMaturity, BigDecimal>(BondMaturity.class);
        var shares = new EnumMap<BondMaturity, BigDecimal>(BondMaturity.class);
        var yields = new EnumMap<BondMaturity, BigDecimal>(BondMaturity.class);
        var mix = config.mixes().get(mixIndex);
        for (var maturity : BondMaturity.values()) {
            // A fee is a fee: bond funds carry the advisory fee and expense ratio like every other account.
            net.put(maturity, BigDecimal.valueOf(realReturns.getOrDefault(maturity, 0.0)).subtract(feeRate));
            shares.put(maturity, orZero(mix.shares().get(maturity)));
            yields.put(maturity, orZero(config.yields().get(maturity)));
        }
        var setup = new PoolStrategy.TentSetup(PoolStrategy.TentSetup.placementFor(order), shares, net, yields);
        return new TentRun(sized.sizing(), buildUp, setup);
    }

    /** Configures the fresh pools for the tent; a tent-free pass ({@code tent == null}) leaves them untouched. */
    static void configure(PoolStrategy pool, @Nullable TentRun tent) {
        if (tent != null) {
            pool.configureTent(tent.setup());
        }
    }

    /**
     * Build-up line: reserve the year's share after contributions and before growth; inert without a tent. In a
     * replay, a build-up year that starts with the stock index below its high reserves at most the year's
     * contributions, so a crash during the build-up cannot be funded from the portfolio that just fell.
     */
    static void topUp(PoolStrategy pool, @Nullable TentRun tent, int year, @Nullable CrashReplayer replay,
                      BigDecimal contributions) {
        if (tent != null) {
            var target = tent.buildUp().targetFor(year);
            if (target != null) {
                // Top-ups buy at the mix shares; sleeves already held are not rebalanced within the tent.
                pool.topUpTent(target, buildUpCap(tent.buildUp(), replay, contributions));
            }
        }
    }

    @Nullable
    private static BigDecimal buildUpCap(BuildUp buildUp, @Nullable CrashReplayer replay, BigDecimal contributions) {
        boolean hasBuildUp = buildUp.retirementYear() > buildUp.start();
        return replay != null && hasBuildUp && replay.isBelowPeak() ? contributions.max(BigDecimal.ZERO) : null;
    }

    /** The side channel of a finished pass: {@code null} for a tent-free pass. */
    @Nullable
    static BondTentResult result(@Nullable TentRun tent, List<BondTentYear> years) {
        return tent == null ? null : new BondTentResult(tent.sizing(), List.copyOf(years));
    }

    /** Appends the tent as it stands after {@code year} was processed; inert without a tent. */
    static void record(List<BondTentYear> years, int year, PoolStrategy pool, @Nullable TentRun tent) {
        if (tent != null) {
            years.add(yearRow(year, pool, tent));
        }
    }

    private static BondTentYear yearRow(int year, PoolStrategy pool, TentRun tent) {
        var byMaturity = pool.tentByMaturity();
        var balances = new BondMaturityValues(
                money(byMaturity.get(BondMaturity.ULTRASHORT)), money(byMaturity.get(BondMaturity.SHORT)),
                money(byMaturity.get(BondMaturity.INTERMEDIATE)), money(byMaturity.get(BondMaturity.LONG)));
        return new BondTentYear(year, balances, money(pool.tentTotal()), tent.buildUp().targetFor(year));
    }

    private static BigDecimal money(@Nullable BigDecimal value) {
        return orZero(value).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    private static BigDecimal money(double value) {
        return BigDecimal.valueOf(value).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    private static BigDecimal rate(double value) {
        return BigDecimal.valueOf(value).setScale(RATE_SCALE, RoundingMode.HALF_UP);
    }

    private static BigDecimal orZero(@Nullable BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
