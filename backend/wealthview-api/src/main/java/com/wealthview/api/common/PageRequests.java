package com.wealthview.api.common;

/**
 * Defensive clamps for {@code page} / {@code size} pagination request params.
 *
 * <p>Spring Data's {@code PageRequest.of(page, size)} computes a {@code long}
 * offset as {@code page * size}, but its repository binding casts back to an
 * {@code int}. Without clamping, an attacker can send {@code ?page=2147483647}
 * and trigger an {@code InvalidDataAccessApiUsageException: Page offset
 * exceeds Integer.MAX_VALUE}, which surfaces as a 500. Both {@link #clampPage}
 * and {@link #clampSize} silently coerce out-of-range values into safe limits.
 */
public final class PageRequests {

    public static final int MAX_PAGE_SIZE = 200;

    /**
     * Upper bound on the {@code page} index. The product
     * {@code MAX_PAGE_INDEX * MAX_PAGE_SIZE} stays comfortably below
     * {@link Integer#MAX_VALUE} so Spring Data's {@code int} offset cast is
     * safe. With these defaults the largest reachable result-set start is
     * 100,000 * 200 = 20,000,000 rows — far beyond any realistic listing.
     */
    public static final int MAX_PAGE_INDEX = 100_000;

    private PageRequests() {
    }

    public static int clampSize(int requested) {
        if (requested < 1) {
            return 1;
        }
        return Math.min(requested, MAX_PAGE_SIZE);
    }

    public static int clampPage(int requested) {
        if (requested < 0) {
            return 0;
        }
        return Math.min(requested, MAX_PAGE_INDEX);
    }
}
