package com.wealthview.projection;

import org.springframework.lang.Nullable;

import com.wealthview.core.projection.dto.LotOwner;
import com.wealthview.projection.TrialSimulator.HouseholdSim;

/**
 * Household task 6: the owner-aware {@code double[5]} pool mechanics for the Monte Carlo trial —
 * the double-land analog of the deterministic engine's {@code OwnerPool}. Layout is
 * {@code {joint-taxable, trad-primary, trad-spouse, roth-primary, roth-spouse}}. Extracted from
 * {@link TrialSimulator} so the trial's tax/withdrawal orchestration and these low-level pool moves
 * (owner-proportional debits, the tax cascade, per-owner RMD forcing, the first-death transition)
 * live in separate, focused types — and so {@link TrialSimulator} stays under the PMD cyclomatic
 * threshold, the no-regression policy.
 *
 * <p>In a single-person trial the spouse slots ({@link #TRAD_S}, {@link #ROTH_S}) stay 0 for the
 * whole trial and every method reduces to the pre-household 3-pool arithmetic bit-for-bit — the
 * byte-identical anchor.
 */
final class McPools {

    /** Named pool indices for the owner-aware {@code double[5]} layout. */
    public static final int JOINT_TAXABLE = 0;
    public static final int TRAD_P = 1;
    public static final int TRAD_S = 2;
    public static final int ROTH_P = 3;
    public static final int ROTH_S = 4;

    private McPools() {}

    /** Seeds the {@code double[5]} pools, carving the spouse's traditional/Roth slice (0 for a null
     * household) out of the config's TOTALS so the primary keeps the remainder. */
    static double[] initPools(double initTaxable, double initTraditional, double initRoth,
                              @Nullable HouseholdSim household) {
        double initTradSpouse = household != null ? household.initTraditionalSpouse() : 0.0;
        double initRothSpouse = household != null ? household.initRothSpouse() : 0.0;
        return new double[] {
                initTaxable,
                initTraditional - initTradSpouse, initTradSpouse,
                initRoth - initRothSpouse, initRothSpouse };
    }

    /** Sum across the five owner-aware pools; equals {@code taxable + traditional + roth} for a
     * single-person trial (spouse slots 0) bit-for-bit. */
    // UseVarargs: {@code pools} is the fixed five-element pool state array, not a varargs list.
    @SuppressWarnings("PMD.UseVarargs")
    static double poolTotal(double[] pools) {
        return pools[JOINT_TAXABLE] + pools[TRAD_P] + pools[TRAD_S] + pools[ROTH_P] + pools[ROTH_S];
    }

    /** Traditional total across both owners. */
    // UseVarargs: {@code pools} is the fixed five-element pool state array, not a varargs list.
    @SuppressWarnings("PMD.UseVarargs")
    static double traditionalTotal(double[] pools) {
        return pools[TRAD_P] + pools[TRAD_S];
    }

    /** Roth total across both owners. */
    // UseVarargs: {@code pools} is the fixed five-element pool state array, not a varargs list.
    @SuppressWarnings("PMD.UseVarargs")
    static double rothTotal(double[] pools) {
        return pools[ROTH_P] + pools[ROTH_S];
    }

    /** Grows the traditional and Roth pools at their per-pool real returns (the joint-taxable pool
     * grows separately, via the lot-aware dividend/interest path). */
    static void growNonTaxablePools(double[] pools, double traditionalReturn, double rothReturn) {
        pools[TRAD_P] *= (1 + traditionalReturn);
        pools[TRAD_S] *= (1 + traditionalReturn);
        pools[ROTH_P] *= (1 + rothReturn);
        pools[ROTH_S] *= (1 + rothReturn);
    }

    /** Floors all five pools at zero after the year's mutations. */
    // UseVarargs: {@code pools} is the fixed five-element pool state array mutated in place.
    @SuppressWarnings("PMD.UseVarargs")
    static void clampNonNegative(double[] pools) {
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
    static void debitPair(double[] pools, int a, int b, double amount) {
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
     * The owner-aware generalization of {@code PoolTaxCascade}: deducts {@code amount} in the fixed
     * taxable → traditional → roth priority order, the traditional and Roth stages split
     * PROPORTIONALLY across their owner slots (see {@link #debitPair}). No pool is driven negative.
     * Returns the taxable dollars drawn (for the caller's FIFO-lot sync). Reduces to the pre-household
     * single-scalar cascade for a single-person trial.
     */
    static double deductCascade5(double[] pools, double amount) {
        double remaining = amount;
        double taxableSold = 0;
        if (remaining > 0 && pools[JOINT_TAXABLE] > 0) {
            double draw = Math.min(remaining, pools[JOINT_TAXABLE]);
            pools[JOINT_TAXABLE] -= draw;
            remaining -= draw;
            taxableSold = draw;
        }
        double tradTotal = traditionalTotal(pools);
        if (remaining > 0 && tradTotal > 0) {
            double draw = Math.min(remaining, tradTotal);
            debitPair(pools, TRAD_P, TRAD_S, draw);
            remaining -= draw;
        }
        double rothTotal = rothTotal(pools);
        if (remaining > 0 && rothTotal > 0) {
            double draw = Math.min(remaining, rothTotal);
            debitPair(pools, ROTH_P, ROTH_S, draw);
        }
        return taxableSold;
    }

    /**
     * Deducts a tax amount from pools in order taxable → traditional → roth via
     * {@link #deductCascade5}, mirroring the taxable-first FIFO-lot sale on {@code lots} to preserve
     * the value invariant (the realized gain is a second-order tax-payment sale, deliberately
     * untaxed). No gross-up (that stays in {@link TrialSimulator}).
     */
    static void deductTaxFromPools(double tax, double[] pools, TaxableLots lots) {
        double taxableSold = deductCascade5(pools, tax);
        if (taxableSold > 0) {
            lots.sellFifo(taxableSold);
        }
    }

    /**
     * Computes this year's RMD from a prior-year-end traditional balance per the IRS Uniform
     * Lifetime Table. Returns 0 before {@code rmdStartAge}, with no balance, or when the table has
     * no distribution period for the age (outside 72-120).
     */
    static double computeYearRmd(double priorTraditional, int age, int rmdStartAge) {
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
    static double forceRmdStream(double[] pools, TaxableLots lots, int slot, double rmd,
                                 @Nullable OrdinaryTaxTable table, double base) {
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
    static RmdStreams forceRmdStreams(double[] pools, TaxableLots lots, @Nullable HouseholdSim household,
                                      double tradPPreGrowth, double tradSPreGrowth, int age,
                                      int primaryRmdStartAge, @Nullable OrdinaryTaxTable table, double base) {
        double rmdP = computeYearRmd(tradPPreGrowth, age, primaryRmdStartAge);
        double forcedP = forceRmdStream(pools, lots, TRAD_P, rmdP, table, base);
        double computed = rmdP;
        double forcedS = 0.0;
        if (household != null) {
            int spouseAge = age + household.spouseAgeOffset();
            double rmdS = computeYearRmd(tradSPreGrowth, spouseAge, household.spouseRmdStartAge());
            computed += rmdS;
            forcedS = forceRmdStream(pools, lots, TRAD_S, rmdS, table, base + forcedP);
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
    static void applyFirstDeathTransition(double[] pools, TaxableLots lots, HouseholdSim household) {
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
