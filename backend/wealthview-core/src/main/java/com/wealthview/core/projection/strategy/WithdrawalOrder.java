package com.wealthview.core.projection.strategy;

import java.util.List;
import java.util.Locale;

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

    /** Pool token for the taxable pool; matches the persisted {@code accounts.account_type} value. */
    public static final String POOL_TAXABLE = "taxable";
    /** Pool token for the tax-deferred pool; matches the persisted {@code accounts.account_type} value. */
    public static final String POOL_TRADITIONAL = "traditional";
    /** Pool token for the Roth pool; matches the persisted {@code accounts.account_type} value. */
    public static final String POOL_ROTH = "roth";

    private static final List<String> TAXABLE_FIRST_SEQUENCE =
            List.of(POOL_TAXABLE, POOL_TRADITIONAL, POOL_ROTH);
    private static final List<String> TRADITIONAL_FIRST_SEQUENCE =
            List.of(POOL_TRADITIONAL, POOL_TAXABLE, POOL_ROTH);
    private static final List<String> ROTH_FIRST_SEQUENCE =
            List.of(POOL_ROTH, POOL_TAXABLE, POOL_TRADITIONAL);

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
     * the pool tokens ({@link #POOL_TAXABLE} / {@link #POOL_TRADITIONAL} / {@link #POOL_ROTH}).
     * Always a permutation of all three pools.
     *
     * <p>{@link #PRO_RATA} and {@link #DYNAMIC_SEQUENCING} are not priority sequences — they
     * allocate proportionally and by bracket space respectively, and every consumer dispatches
     * them ahead of the ordered draw. They report the taxable-first sequence so the paths that
     * fall through to a plain sequence (dynamic sequencing with no bracket rate configured, and
     * the Monte Carlo trial path, which has no proportional mode) keep their documented
     * taxable-first fallback rather than drawing nothing.
     */
    public List<String> drawSequence() {
        return switch (this) {
            case TRADITIONAL_FIRST -> TRADITIONAL_FIRST_SEQUENCE;
            case ROTH_FIRST -> ROTH_FIRST_SEQUENCE;
            case TAXABLE_FIRST, PRO_RATA, DYNAMIC_SEQUENCING -> TAXABLE_FIRST_SEQUENCE;
        };
    }
}
