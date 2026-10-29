package com.wealthview.projection;

/**
 * HP1: the joint-taxable pool's initial seed split by owner (basis + value per
 * {@code joint}/{@code primary}/{@code spouse}), so the Monte Carlo lots can be seeded with owner
 * tags for an exact first-death step-up. {@link #EMPTY} (all zero) selects the single-aggregate
 * seed path in {@link TrialSimulator#simulateTrial} — used for single-person and all-joint taxable
 * pools, whose lots stay a single joint lot (byte-identical). A mixed-ownership pool carries the primary and/or
 * spouse slices, triggering per-owner tagged seeding.
 */
record TaxableSeed(double jointBasis, double jointValue,
                   double primaryBasis, double primaryValue,
                   double spouseBasis, double spouseValue) {
    static final TaxableSeed EMPTY = new TaxableSeed(0, 0, 0, 0, 0, 0);

    boolean hasOwnedSlices() {
        return primaryValue > 0 || spouseValue > 0;
    }
}
