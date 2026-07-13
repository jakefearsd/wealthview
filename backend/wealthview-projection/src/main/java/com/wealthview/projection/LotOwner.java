package com.wealthview.projection;

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
 */
enum LotOwner {
    /** A joint (community/tenancy) taxable account — and the default for every reinvested household
     * flow (dividends, interest, RMD after-tax remainder, surplus reinvestment) and single-person lot. */
    JOINT,
    /** A taxable account owned solely by the primary. */
    PRIMARY,
    /** A taxable account owned solely by the spouse. */
    SPOUSE;

    /** Maps a normalized owner category ({@code "joint"}/{@code "primary"}/{@code "spouse"}, per
     * {@code PoolStrategy#ownerCategory}) to its lot tag; anything else defaults to {@link #PRIMARY}. */
    static LotOwner fromCategory(String ownerCategory) {
        return switch (ownerCategory) {
            case "joint" -> JOINT;
            case "spouse" -> SPOUSE;
            default -> PRIMARY;
        };
    }

    /** Decodes a lot's stored owner slot (this enum's {@link #ordinal()}). */
    static LotOwner fromCode(int code) {
        return values()[code];
    }

    /** Maps a {@link PersonId} (decedent/survivor) to its lot tag; a lot is never {@link #JOINT}
     * for an individual person. */
    static LotOwner forPerson(PersonId person) {
        return person == PersonId.SPOUSE ? SPOUSE : PRIMARY;
    }
}
