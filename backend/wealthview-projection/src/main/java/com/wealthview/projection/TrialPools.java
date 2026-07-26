package com.wealthview.projection;

import org.springframework.lang.Nullable;

import com.wealthview.core.projection.dto.LotOwner;
import com.wealthview.projection.TrialSimulator.HouseholdSim;
import com.wealthview.projection.TrialSimulator.SimulationConfig;

/**
 * Task 17: the mutable per-trial pool state -- the double-land analog of the deterministic
 * engine's {@code OwnerPool}. Formerly {@code McPools}, a stateless collection of static methods
 * threading a {@code (double[] pools, TaxableLots lots)} pair through 15 signatures (11 in
 * {@link TrialSimulator}, 4 here); consolidated into ONE mutable object per trial so that pair
 * moves as a single parameter instead. Extracted from {@link TrialSimulator} so the trial's
 * tax/withdrawal orchestration and these low-level pool moves (owner-proportional debits, the tax
 * cascade, per-owner RMD forcing, the first-death transition) live in separate, focused types --
 * and so {@link TrialSimulator} stays under the PMD cyclomatic threshold, the no-regression
 * policy.
 *
 * <p>Layout is {@code {joint-taxable, trad-primary, trad-spouse, roth-primary, roth-spouse}}. In a
 * single-person trial the spouse slots ({@link #TRAD_S}, {@link #ROTH_S}) stay 0 for the whole
 * trial and every method reduces to the pre-household 3-pool arithmetic bit-for-bit -- the
 * byte-identical anchor.
 *
 * <p><b>Allocation discipline (hot loop):</b> exactly ONE {@code TrialPools} is allocated per
 * trial, in {@link #seed}, and mutated in place for the trial's whole duration -- no further
 * allocations and no defensive copies of {@link #pools} are ever taken inside the per-year loop.
 *
 * <p><b>Value invariant:</b> {@link #lots}, the joint-taxable pool's parallel FIFO-lot view (Task
 * 6), is kept in lock-step with {@code pools[JOINT_TAXABLE]} BY CONSTRUCTION at every call site in
 * {@link TrialSimulator} that mutates both (equal debits/credits on each side -- see the
 * dividend/withdrawal/tax helpers). It is only ever manually RE-PINNED at one seam -- household
 * mixed-ownership seeding in {@link #seed}, where the per-owner lot split can't be guaranteed to
 * sum to the config's aggregate exactly -- so that is the only seam this class asserts the
 * invariant at. Adding a per-year reconciliation check anywhere else would be pure hot-loop cost
 * for an invariant that already holds by construction.
 */
final class TrialPools {

    /** Named pool indices for the owner-aware {@code double[5]} layout. */
    static final int JOINT_TAXABLE = 0;
    static final int TRAD_P = 1;
    static final int TRAD_S = 2;
    static final int ROTH_P = 3;
    static final int ROTH_S = 4;

    /** Floating-point slack for the seed-time value-invariant assertion in {@link #seed}. */
    private static final double SEED_INVARIANT_EPSILON = 1e-6;

    /** The five owner-aware pool balances. Private -- every mutation and read {@link
     * TrialSimulator} needs is a small instance method below, so the value invariant with
     * {@link #lots} stays enforceable in ONE place instead of scattered across call sites. Methods
     * are plain scalar reads/single-array-slot writes, so this costs nothing beyond a field access
     * once JIT-inlined -- zero per-year allocation or copy overhead either way. */
    private final double[] pools;

    /** The joint-taxable pool's parallel FIFO-lot view (Task 6). Private, same reasoning as
     * {@link #pools}. */
    private final TaxableLots lots;

    // ArrayIsStoredDirectly: pools is a fresh 5-element array the caller (TrialPools.seed, or a
    // test constructing a fixture directly) hands off exclusively for this trial's exclusive
    // mutation -- the same "engine-internal, not attacker-controlled, one-per-trial" contract as
    // SimulationConfig's per-year arrays. A defensive copy here would be a per-trial allocation
    // this hot-loop type exists specifically to avoid.
    @SuppressWarnings("PMD.ArrayIsStoredDirectly")
    TrialPools(double[] pools, TaxableLots lots) {
        this.pools = pools;
        this.lots = lots;
    }

    /**
     * Seeds a new trial's pools and FIFO lots together (formerly {@code McPools.initPools} +
     * {@code TrialSimulator.seedTaxableLots}, unified here so the mixed-ownership household
     * branch -- the one seam that manually re-pins the value invariant -- has both sides in hand
     * at once). Carves the spouse's traditional/Roth slice (0 for a null household) out of the
     * config's TOTALS so the primary keeps the remainder, then seeds the joint-taxable lots: one
     * aggregate joint lot from the config totals for a single-person or all-joint taxable pool
     * (byte-identical to pre-HP1), or one tagged lot per owner category for a mixed-ownership
     * household (HP1), re-pinning {@code pools[JOINT_TAXABLE]} to the lots' total so the two start
     * EXACTLY in sync.
     */
    static TrialPools seed(SimulationConfig config, @Nullable HouseholdSim household) {
        double initTradSpouse = household != null ? household.initTraditionalSpouse() : 0.0;
        double initRothSpouse = household != null ? household.initRothSpouse() : 0.0;
        double[] pools = {
                config.initTaxable(),
                config.initTraditional() - initTradSpouse, initTradSpouse,
                config.initRoth() - initRothSpouse, initRothSpouse };
        TaxableLots lots = new TaxableLots();
        TrialPools trialPools = new TrialPools(pools, lots);

        if (household != null && household.taxableSeed().hasOwnedSlices()) {
            var taxableSeed = household.taxableSeed();
            lots.addLot(taxableSeed.jointBasis(), taxableSeed.jointValue(), LotOwner.JOINT);
            lots.addLot(taxableSeed.primaryBasis(), taxableSeed.primaryValue(), LotOwner.PRIMARY);
            lots.addLot(taxableSeed.spouseBasis(), taxableSeed.spouseValue(), LotOwner.SPOUSE);
            pools[JOINT_TAXABLE] = lots.totalValue();
            // Value invariant (HP1): the ONE seam that manually re-pins pools[JOINT_TAXABLE] to
            // lots.totalValue() -- assertion-only (compiled out unless -ea), documenting the seam
            // without adding any per-year reconciliation cost elsewhere.
            assert Math.abs(pools[JOINT_TAXABLE] - lots.totalValue()) < SEED_INVARIANT_EPSILON
                    : "value invariant lots.totalValue() == pools[JOINT_TAXABLE] violated at seed time";
        } else {
            lots.addLot(config.initTaxableBasis(), config.initTaxable());
        }
        return trialPools;
    }

    /** Sum across the five owner-aware pools; equals {@code taxable + traditional + roth} for a
     * single-person trial (spouse slots 0) bit-for-bit. */
    double poolTotal() {
        return pools[JOINT_TAXABLE] + pools[TRAD_P] + pools[TRAD_S] + pools[ROTH_P] + pools[ROTH_S];
    }

    /** Traditional total across both owners. */
    double traditionalTotal() {
        return pools[TRAD_P] + pools[TRAD_S];
    }

    /** Roth total across both owners. */
    double rothTotal() {
        return pools[ROTH_P] + pools[ROTH_S];
    }

    /** The joint-taxable pool balance. */
    double taxable() {
        return pools[JOINT_TAXABLE];
    }

    /** The primary owner's traditional balance alone (household task 6 per-owner RMD basis --
     * snapshotted BEFORE this year's growth, so it can't be folded into {@link #traditionalTotal}). */
    double traditionalPrimary() {
        return pools[TRAD_P];
    }

    /** The spouse's traditional balance alone -- 0 for a single-person trial. See
     * {@link #traditionalPrimary}. */
    double traditionalSpouse() {
        return pools[TRAD_S];
    }

    /** Grows the traditional and Roth pools at their per-pool real returns (the joint-taxable pool
     * grows separately, via the lot-aware dividend/interest path). */
    void growNonTaxablePools(double traditionalReturn, double rothReturn) {
        pools[TRAD_P] *= (1 + traditionalReturn);
        pools[TRAD_S] *= (1 + traditionalReturn);
        pools[ROTH_P] *= (1 + rothReturn);
        pools[ROTH_S] *= (1 + rothReturn);
    }

    /** Floors all five pools at zero after the year's mutations. */
    void clampNonNegative() {
        pools[JOINT_TAXABLE] = Math.max(0, pools[JOINT_TAXABLE]);
        pools[TRAD_P] = Math.max(0, pools[TRAD_P]);
        pools[TRAD_S] = Math.max(0, pools[TRAD_S]);
        pools[ROTH_P] = Math.max(0, pools[ROTH_P]);
        pools[ROTH_S] = Math.max(0, pools[ROTH_S]);
    }

    /**
     * Debits {@code amount} from an owner pool pair (leading slot {@code a} = primary, trailing slot
     * {@code b} = spouse) split PROPORTIONALLY by balance, mirroring the deterministic
     * {@code OwnerPool.debitProportional}: the leading owner's share is {@code amount × balA ÷ total}
     * and the trailing owner takes the remainder ({@code amount - shareA}), so the pair's combined
     * drop is {@code amount} — exact after typical 4-decimal-place currency rounding downstream (see
     * {@code Money.SCALE}), and empirically exact at double precision for realistic dollar
     * magnitudes, though (unlike {@code OwnerPool.debitProportional}'s BigDecimal remainder, which
     * IS exact) IEEE-754 {@code double} subtraction/addition is not strictly associative, so this is
     * not a guaranteed bit-identity in the general case. When the trailing slot is 0 (single-person,
     * and the survivor's pool after rollover) {@code balA ÷ total == 1.0} exactly, so this reduces to
     * {@code pools[a] -= amount} bit-for-bit. A non-positive pair total puts the whole amount on the
     * leading slot (may drive it negative; the caller clamps later).
     */
    void debitPair(int a, int b, double amount) {
        double total = pools[a] + pools[b];
        if (total <= 0) {
            pools[a] -= amount;
            return;
        }
        double shareA = amount * pools[a] / total;
        pools[a] -= shareA;
        pools[b] -= amount - shareA;
    }

    /**
     * Debits {@code amount} from the joint-taxable pool AND sells the same amount FIFO from
     * {@link #lots} in one lockstep call -- the recurring pattern (spending sale, tax-payment sale,
     * cash-reserve churn) that keeps the value invariant {@code lots.totalValue() ==
     * pools[JOINT_TAXABLE]} intact by construction. Returns the realized FIFO gain; several callers
     * deliberately discard it (second-order tax-payment/churn sales are untaxed by design).
     */
    double sellTaxable(double amount) {
        pools[JOINT_TAXABLE] -= amount;
        return lots.sellFifo(amount);
    }

    /** Credits {@code amount} to the joint-taxable pool AND adds it as a fresh at-cost lot --
     * the surplus-deposit half of the {@link #sellTaxable} lockstep pattern. */
    void creditTaxable(double amount) {
        pools[JOINT_TAXABLE] += amount;
        lots.addLot(amount);
    }

    /**
     * Debits {@code amount} from the joint-taxable pool (via {@link #sellTaxable}, gain discarded --
     * second-order churn), then spills any resulting negative balance into traditional
     * (proportional across owners, household task 6) and clamps taxable back to 0. Used by the
     * up-market cash-reserve replenishment draw, which can overdraw taxable when the target cash
     * level exceeds what taxable alone can fund.
     */
    void debitTaxableWithTraditionalSpillover(double amount) {
        sellTaxable(amount);
        if (pools[JOINT_TAXABLE] < 0) {
            debitPair(TRAD_P, TRAD_S, -pools[JOINT_TAXABLE]);
            pools[JOINT_TAXABLE] = 0;
        }
    }

    /**
     * Converts {@code amount} from traditional to Roth, split PROPORTIONALLY by owner balance
     * (household task 6): debits {@link #TRAD_P}/{@link #TRAD_S} and credits {@link #ROTH_P}/
     * {@link #ROTH_S} by the same per-owner share -- a conversion stays within the owner.
     * Single-person: the whole amount moves TRAD_P -> ROTH_P bit-for-bit. Caller guarantees
     * {@code traditionalTotal() > 0} (a zero-traditional trial never reaches this call).
     */
    void convertToRoth(double amount) {
        double tradTotal = traditionalTotal();
        double shareP = amount * pools[TRAD_P] / tradTotal;
        double shareS = amount - shareP;
        pools[TRAD_P] -= shareP;
        pools[ROTH_P] += shareP;
        pools[TRAD_S] -= shareS;
        pools[ROTH_S] += shareS;
    }

    /** Merges the oldest FIFO lots down to {@code cap} once the year's lot mutations are done --
     * perf safety valve for very long horizons (see {@code TaxableLots#consolidateIfNeeded}). */
    void consolidateLotsIfNeeded(int cap) {
        lots.consolidateIfNeeded(cap);
    }

    /** Audit C1: the year's taxable-pool distribution split between the equity share (qualified
     * dividend, taxed LTCG) and the bond+cash share (ordinary interest, taxed ORDINARY). */
    record TaxableYieldSplit(double dividendIncome, double interestIncome) {}

    /**
     * Grows the taxable pool by {@code taxableReturn}: the existing lots appreciate at
     * {@code (taxableReturn − blendedYield)} and the distribution is reinvested as a fresh at-cost
     * lot, booked as the residual to the exact post-growth scalar so {@code pools[JOINT_TAXABLE]}
     * still grows at precisely {@code taxableReturn} REGARDLESS of the split (bit-identical to the
     * pre-lots path when both yields are 0).
     *
     * <p>Audit C1: the distribution is then split between qualified-dividend income (the equity
     * share, {@code taxableEquityShare}, at {@code dividendYield} -- taxed LTCG, unchanged from
     * pre-C1) and ordinary-interest income (the remaining bond+cash share, at {@code interestYield}
     * -- taxed ORDINARY instead). {@code taxableEquityShare == 1.0} (bond share 0, every pre-C1
     * caller via the back-compat constructors) takes a SEPARATE code path bit-identical to the old
     * single-dividendYield growth path, avoiding any floating-point division/rounding drift for the
     * single most common account shape (the backward-compat anchor).
     */
    TaxableYieldSplit growTaxable(double taxableReturn, double dividendYield, double interestYield,
                                  double taxableEquityShare) {
        pools[JOINT_TAXABLE] *= (1 + taxableReturn);
        double bondShare = 1 - taxableEquityShare;

        if (bondShare == 0) {
            lots.grow(taxableReturn - dividendYield);
            double dividendIncome = Math.max(0, pools[JOINT_TAXABLE] - lots.totalValue());
            lots.addLot(dividendIncome);
            return new TaxableYieldSplit(dividendIncome, 0);
        }

        double equityYieldRate = taxableEquityShare * dividendYield;
        double blendedYield = equityYieldRate + bondShare * interestYield;
        lots.grow(taxableReturn - blendedYield);
        double totalDistribution = Math.max(0, pools[JOINT_TAXABLE] - lots.totalValue());
        lots.addLot(totalDistribution);

        if (blendedYield == 0) {
            return new TaxableYieldSplit(0, 0);
        }
        double dividendPortion = totalDistribution * equityYieldRate / blendedYield;
        return new TaxableYieldSplit(dividendPortion, totalDistribution - dividendPortion);
    }

    /**
     * The owner-aware generalization of {@code PoolTaxCascade}: deducts {@code amount} in the fixed
     * taxable → traditional → roth priority order, the traditional and Roth stages split
     * PROPORTIONALLY across their owner slots (see {@link #debitPair}). No pool is driven negative.
     * Returns the taxable dollars drawn (for the caller's FIFO-lot sync). Reduces to the pre-household
     * single-scalar cascade for a single-person trial.
     */
    private double deductCascade5(double amount) {
        double remaining = amount;
        double taxableSold = 0;
        if (remaining > 0 && pools[JOINT_TAXABLE] > 0) {
            double draw = Math.min(remaining, pools[JOINT_TAXABLE]);
            pools[JOINT_TAXABLE] -= draw;
            remaining -= draw;
            taxableSold = draw;
        }
        double tradTotal = traditionalTotal();
        if (remaining > 0 && tradTotal > 0) {
            double draw = Math.min(remaining, tradTotal);
            debitPair(TRAD_P, TRAD_S, draw);
            remaining -= draw;
        }
        double rothTotal = rothTotal();
        if (remaining > 0 && rothTotal > 0) {
            double draw = Math.min(remaining, rothTotal);
            debitPair(ROTH_P, ROTH_S, draw);
        }
        return taxableSold;
    }

    /**
     * Deducts a tax amount from pools in order taxable → traditional → roth via
     * {@link #deductCascade5}, mirroring the taxable-first FIFO-lot sale on {@link #lots} to preserve
     * the value invariant (the realized gain is a second-order tax-payment sale, deliberately
     * untaxed). No gross-up (that stays in {@link TrialSimulator}).
     */
    void deductTaxFromPools(double tax) {
        double taxableSold = deductCascade5(tax);
        if (taxableSold > 0) {
            lots.sellFifo(taxableSold);
        }
    }

    /**
     * Computes this year's RMD from a prior-year-end traditional balance per the IRS Uniform
     * Lifetime Table. Returns 0 before {@code rmdStartAge}, with no balance, or when the table has
     * no distribution period for the age (outside 72-120). Pure (touches no pool/lot state), kept
     * static since {@link #forceRmdStreams} calls it once per owner before any mutation this year.
     */
    private static double computeYearRmd(double priorTraditional, int age, int rmdStartAge) {
        if (age < rmdStartAge || priorTraditional <= 0) {
            return 0;
        }
        double divisor = RmdCalculator.distributionPeriod(age);
        return divisor > 0 ? priorTraditional / divisor : 0;
    }

    /**
     * Forces one owner's RMD out of their traditional slot into the joint taxable pool (after-tax
     * remainder reinvested at cost; the distribution tax leaks out of the portfolio). Returns the
     * amount actually distributed (0 when {@code rmd <= 0} or the slot is exhausted).
     */
    private double forceRmdStream(int slot, double rmd, @Nullable OrdinaryTaxTable table, double base) {
        if (rmd <= 0) {
            return 0;
        }
        double extra = Math.min(rmd, pools[slot]);
        if (extra > 0) {
            pools[slot] -= extra;
            double taxExtra = table != null ? table.incrementalTax(base, extra) : 0;
            double reinvested = extra - taxExtra;
            pools[JOINT_TAXABLE] += reinvested;
            lots.addLot(reinvested);
        }
        return extra;
    }

    /** The year's two RMD figures: {@code computed} is the summed pre-force RMD (drives the
     * dynamic-sequencing bracket-space calc); {@code forced} the summed amount actually distributed. */
    record RmdStreams(double computed, double forced) {}

    /**
     * Household task 6 — runs BOTH owners' RMD streams in the pinned ordinary-stack order
     * (base+interest → RMD-P → RMD-S). Each stream keys off its owner's own prior-year balance, age,
     * and SECURE-2.0 start age; the spouse stream stacks on the primary's forced amount. For a
     * single-person trial the spouse balance/stream is 0, so this reduces to the single stream.
     */
    RmdStreams forceRmdStreams(@Nullable HouseholdSim household,
                               double tradPPreGrowth, double tradSPreGrowth, int age,
                               int primaryRmdStartAge, @Nullable OrdinaryTaxTable table, double base) {
        double rmdP = computeYearRmd(tradPPreGrowth, age, primaryRmdStartAge);
        double forcedP = forceRmdStream(TRAD_P, rmdP, table, base);
        double computed = rmdP;
        double forcedS = 0.0;
        if (household != null) {
            int spouseAge = age + household.spouseAgeOffset();
            double rmdS = computeYearRmd(tradSPreGrowth, spouseAge, household.spouseRmdStartAge());
            computed += rmdS;
            forcedS = forceRmdStream(TRAD_S, rmdS, table, base + forcedP);
        }
        return new RmdStreams(computed, forcedP + forcedS);
    }

    /**
     * The first-death transition on the MC pools, mirroring the deterministic
     * {@code PoolStrategy.MultiPool.applyFirstDeathTransition}: the deceased owner's traditional and
     * Roth balances roll into the survivor's own pools (conservation-preserving), then the
     * joint-taxable lots step up EXACTLY per owner (HP1) — each lot by its own owner's statutory rate
     * (joint at {@code jointStepUpFactor}, the decedent's own lots fully, the survivor's not at all),
     * with decedent-owned lots retagged to the survivor. The filing-status flip is handled at build
     * time via the per-year tax tables, not here.
     */
    void applyFirstDeathTransition(HouseholdSim household) {
        if (household.survivorIsPrimary()) {
            pools[TRAD_P] += pools[TRAD_S];
            pools[TRAD_S] = 0.0;
            pools[ROTH_P] += pools[ROTH_S];
            pools[ROTH_S] = 0.0;
        } else {
            pools[TRAD_S] += pools[TRAD_P];
            pools[TRAD_P] = 0.0;
            pools[ROTH_S] += pools[ROTH_P];
            pools[ROTH_P] = 0.0;
        }
        LotOwner deceased = household.survivorIsPrimary() ? LotOwner.SPOUSE : LotOwner.PRIMARY;
        lots.stepUpByOwner(deceased, household.jointStepUpFactor());
    }
}
