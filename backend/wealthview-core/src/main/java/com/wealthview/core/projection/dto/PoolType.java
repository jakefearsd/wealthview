package com.wealthview.core.projection.dto;

import java.util.Locale;

/**
 * The three tax-treatment pools a projection account can belong to. The wire value (lowercase,
 * via {@link #fromString}/{@link #wireValue}) matches the persisted {@code accounts.account_type}
 * column, which carries a database CHECK constraint restricting it to exactly this closed set
 * (V012: {@code account_type IN ('traditional', 'roth', 'taxable')}) — every row read back through
 * {@link ProjectionAccountInput#accountType()} is therefore already guaranteed to be one of these
 * three tokens by the time it reaches {@link ProjectionAccountInput#poolType()}.
 *
 * <p>Task 16: replaces the hand-written {@code PoolStrategy.POOL_TAXABLE}/{@code POOL_TRADITIONAL}/
 * {@code POOL_ROTH} string constants and every silent {@code default -> taxable} switch dispatch
 * that grouped/branched on the raw {@link ProjectionAccountInput#accountType()} string.
 */
public enum PoolType {
    TAXABLE,
    TRADITIONAL,
    ROTH;

    /**
     * Parses a wire-format pool token (case-insensitive). Throws on anything outside the closed
     * set {@code taxable}/{@code traditional}/{@code roth} (including {@code null}) — unlike
     * {@code WithdrawalOrder#fromString}, there is no silent default: every construction path for
     * {@link ProjectionAccountInput} is validated at the input boundary (the persisted-row CHECK
     * constraint, plus {@code ScenarioCrudService}'s owner/account-type request validation), so an
     * unrecognized token here indicates that boundary was bypassed and should fail loudly rather
     * than silently degrade to taxable.
     */
    public static PoolType fromString(String value) {
        if (value == null) {
            throw new IllegalArgumentException("Unknown pool type: null");
        }
        return switch (value.toLowerCase(Locale.US)) {
            case "taxable" -> TAXABLE;
            case "traditional" -> TRADITIONAL;
            case "roth" -> ROTH;
            default -> throw new IllegalArgumentException("Unknown pool type: " + value);
        };
    }

    /** The lowercase wire token this constant round-trips through {@link #fromString}. */
    public String wireValue() {
        return name().toLowerCase(Locale.US);
    }
}
