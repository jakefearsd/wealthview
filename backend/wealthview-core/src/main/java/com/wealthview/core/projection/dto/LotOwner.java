package com.wealthview.core.projection.dto;

import java.util.Locale;

import com.wealthview.core.projection.household.PersonId;

/**
 * Household polish HP1: the owner tag carried on a single taxable FIFO lot. It is pure metadata —
 * consumed ONLY by the first-death basis step-up ({@code TaxableLotsBd#stepUpByOwner} /
 * {@code TaxableLots#stepUpByOwner}). FIFO ordering, sells, growth, and the dividend/interest split
 * never read it, so tagging changes nothing outside the transition year.
 *
 * <p>Replaces the T4/T5 owner-agnostic commingled pool + single blended step-up factor: the taxable
 * lots stay one commingled FIFO set, but each lot now knows whose money seeded it, so the step-up can
 * apply the EXACT per-owner statutory rate (joint → community/common-law rate; decedent-owned → full;
 * survivor-owned → none) instead of an initial-balance-weighted average of the three.
 *
 * <p>The tag is stored as this enum's {@link #ordinal()} in each lot's third numeric slot
 * ({@code double} / {@code BigDecimal}) to keep the lot representation a flat primitive tuple (no
 * per-lot object churn in the Monte Carlo hot loop). The ordinal is therefore part of that on-slot
 * encoding: <b>do not reorder these constants.</b>
 *
 * <p>Task 16: promoted from {@code wealthview-projection}'s package-private lot-representation
 * detail to a public core vocabulary type, so {@link ProjectionAccountInput#ownerType()} can expose
 * it directly — the SAME three-way {@code joint}/{@code primary}/{@code spouse} category the
 * deleted {@code PoolStrategy.ownerCategory} bridge used to normalize by hand.
 */
public enum LotOwner {
    /** A joint (community/tenancy) taxable account — and the default for every reinvested household
     * flow (dividends, interest, RMD after-tax remainder, surplus reinvestment) and single-person lot. */
    JOINT,
    /** A taxable account owned solely by the primary. */
    PRIMARY,
    /** A taxable account owned solely by the spouse. */
    SPOUSE;

    /**
     * Parses a wire-format owner token (case-insensitive): {@code "joint"}/{@code "primary"}/
     * {@code "spouse"}. Throws on anything else (including {@code null}) — every construction path
     * for {@link ProjectionAccountInput} is validated at the input boundary ({@code
     * chk_projection_accounts_owner} plus {@code ScenarioCrudService#validateAccountOwner}), so an
     * unrecognized token indicates that boundary was bypassed. Replaces the former {@code
     * PoolStrategy.ownerCategory}/{@code LotOwner.fromCategory} pair, which silently defaulted an
     * unrecognized owner to {@link #PRIMARY} instead of failing.
     */
    public static LotOwner fromString(String owner) {
        if (owner == null) {
            throw new IllegalArgumentException("Unknown owner: null");
        }
        return switch (owner.toLowerCase(Locale.US)) {
            case "joint" -> JOINT;
            case "primary" -> PRIMARY;
            case "spouse" -> SPOUSE;
            default -> throw new IllegalArgumentException("Unknown owner: " + owner);
        };
    }

    /** Decodes a lot's stored owner slot (this enum's {@link #ordinal()}). */
    public static LotOwner fromCode(int code) {
        return values()[code];
    }

    /** Maps a {@link PersonId} (decedent/survivor) to its lot tag; a lot is never {@link #JOINT}
     * for an individual person. */
    public static LotOwner forPerson(PersonId person) {
        return person == PersonId.SPOUSE ? SPOUSE : PRIMARY;
    }
}
