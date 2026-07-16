package com.wealthview.core.projection.dto;

import java.math.BigDecimal;

/**
 * Sub-project B (stochastic mortality), task 8: the wire-facing summary of one stochastic-mortality
 * evaluation pass (tasks 6/7's aggregated result) -- the lifetime (unconditional) success
 * probability, the longevity-conditional success rate, and the first/second death-age
 * distributions. Nested (NOT {@code @JsonUnwrapped}) on {@link GuardrailProfileResponse}: unlike
 * {@link GuardrailProfileResponse.Disclosure}'s flat top-level keys, this block serializes as its
 * own {@code stochastic_mortality} object.
 *
 * <p>{@code null} whenever the run did not opt into stochastic mortality (the toggle is off, a
 * single-person scenario, or a persisted-profile read that predates this field) -- the wire key is
 * then simply {@code null} on the response, byte-identical to every pre-task-8 response.
 *
 * <p>Built directly by {@code GuardrailResponseBuilder} (wealthview-projection) from its
 * package-private {@code StochasticMortalitySummary}: module dependency direction is strictly
 * projection -&gt; core, so core cannot reference that projection-internal type in a static factory
 * here (no {@code from(StochasticMortalitySummary)} on this record) -- the same reason
 * {@link RothConversionScheduleResponse} and {@link GuardrailProfileResponse.Disclosure} are built
 * with a plain constructor call from projection rather than a core-side {@code from(...)} factory
 * over a projection type.
 */
public record StochasticMortalityResponse(
        BigDecimal lifetimeSuccessProbability,
        LongevityConditionalResponse longevityConditional,
        AgeDistributionResponse firstDeathAge,
        AgeDistributionResponse secondDeathAge) {

    /**
     * The success rate conditional on the household surviving to a given age.
     *
     * @param age the longevity threshold ("at least one spouse alive at this age") the subset was
     *         conditioned on.
     * @param probability the success rate WITHIN the subset of trials whose survivor reached
     *         {@code age}.
     * @param trialFraction what share of ALL trials qualified for the subset -- how much sample
     *         weight {@code probability} itself carries.
     */
    public record LongevityConditionalResponse(int age, BigDecimal probability, BigDecimal trialFraction) {}

    /** A percentile summary of a sampled death-age distribution, reported as whole years. */
    public record AgeDistributionResponse(int p10, int median, int p90) {}
}
