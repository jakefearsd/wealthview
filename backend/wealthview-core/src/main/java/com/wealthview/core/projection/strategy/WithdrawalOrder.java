package com.wealthview.core.projection.strategy;

import java.util.List;
import java.util.Locale;

import com.wealthview.core.projection.dto.PoolType;

/**
 * The order in which retirement withdrawals draw down the taxable / traditional / roth pools.
 *
 * <p>{@link #drawSequence()} is the single source of truth for the pool priority each order
 * implies; every engine that walks the pools greedily (the deterministic multi-pool projection,
 * the Monte Carlo trial simulator, and Roth-conversion scoring) consumes it instead of
 * re-deriving its own permutation.
 */
public enum WithdrawalOrder {
    TAXABLE_FIRST,
    TRADITIONAL_FIRST,
    ROTH_FIRST,
    PRO_RATA,
    DYNAMIC_SEQUENCING;

    private static final List<PoolType> TAXABLE_FIRST_SEQUENCE =
            List.of(PoolType.TAXABLE, PoolType.TRADITIONAL, PoolType.ROTH);
    private static final List<PoolType> TRADITIONAL_FIRST_SEQUENCE =
            List.of(PoolType.TRADITIONAL, PoolType.TAXABLE, PoolType.ROTH);
    private static final List<PoolType> ROTH_FIRST_SEQUENCE =
            List.of(PoolType.ROTH, PoolType.TAXABLE, PoolType.TRADITIONAL);

    public static WithdrawalOrder fromString(String value) {
        if (value == null) {
            return TAXABLE_FIRST;
        }
        return switch (value.toLowerCase(Locale.US)) {
            case "taxable_first" -> TAXABLE_FIRST;
            case "traditional_first" -> TRADITIONAL_FIRST;
            case "roth_first" -> ROTH_FIRST;
            case "pro_rata" -> PRO_RATA;
            case "dynamic_sequencing" -> DYNAMIC_SEQUENCING;
            default -> TAXABLE_FIRST;
        };
    }

    /**
     * The pools in the priority order this withdrawal order draws them, as an immutable list of
     * {@link PoolType}. Always a permutation of all three pools.
     *
     * <p>{@link #PRO_RATA} and {@link #DYNAMIC_SEQUENCING} are not priority sequences — they
     * allocate proportionally and by bracket space respectively, and every consumer dispatches
     * them ahead of the ordered draw. They report the taxable-first sequence so the paths that
     * fall through to a plain sequence (dynamic sequencing with no bracket rate configured, and
     * the Monte Carlo trial path, which has no proportional mode) keep their documented
     * taxable-first fallback rather than drawing nothing.
     */
    public List<PoolType> drawSequence() {
        return switch (this) {
            case TRADITIONAL_FIRST -> TRADITIONAL_FIRST_SEQUENCE;
            case ROTH_FIRST -> ROTH_FIRST_SEQUENCE;
            case TAXABLE_FIRST, PRO_RATA, DYNAMIC_SEQUENCING -> TAXABLE_FIRST_SEQUENCE;
        };
    }
}
