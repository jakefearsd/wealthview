package com.wealthview.projection;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.springframework.lang.Nullable;

import com.wealthview.core.projection.CapitalMarketAssumptionsProvider;
import com.wealthview.core.projection.dto.AssetClass;
import com.wealthview.core.projection.dto.ProjectionAccountInput;
import com.wealthview.core.projection.dto.ScenarioParams;
import com.wealthview.core.projection.tax.CapitalGainsTaxCalculator;
import com.wealthview.core.projection.tax.FederalTaxCalculator;
import com.wealthview.core.projection.tax.FilingStatus;
import com.wealthview.core.projection.tax.TaxCalculationStrategy;

/** Builds the deterministic engine's fresh {@link PoolStrategy} for each pass from the scenario's parameters. */
final class PoolStrategyAssembler {

    /**
     * Fallback inflation assumption used to convert a user's NOMINAL expected-return override into a
     * REAL return via {@code (1+nominal)/(1+inflationRate)-1} when the scenario itself has no
     * inflation rate. The whole projection runs in real (today's-dollars) terms, and overrides must be
     * deflated at the SAME rate as income/Social Security/spending — the scenario's own inflation rate
     * — so that all cash flows share one real-dollar frame. {@code DeterministicProjectionEngine.resolveProjectionParams} already
     * defaults a missing scenario rate to this same value, so in practice this constant is only ever
     * used as that upstream default flowing through, never as an independent override (allocation
     * accounts already grow at real geometric means and are unaffected by this constant either way).
     */
    private static final BigDecimal CMA_INFLATION_RATE = new BigDecimal("0.025");

    private final ScenarioParamsParser paramsParser = new ScenarioParamsParser();
    /**
     * The raw federal calculator, retained separately from the tax strategy (which wraps the same instance) so it
     * can be threaded into {@code PoolConfig} for the LTCG stacking-floor fix -- MultiPool needs the federal
     * standard deduction even when isolating LTCG computation from the ordinary tax strategy.
     */
    @Nullable
    private final FederalTaxCalculator federalTaxCalculator;
    @Nullable
    private final CapitalGainsTaxCalculator capitalGainsTaxCalculator;
    @Nullable
    private final CapitalMarketAssumptionsProvider capitalMarketAssumptions;

    PoolStrategyAssembler(@Nullable FederalTaxCalculator federalTaxCalculator,
                          @Nullable CapitalGainsTaxCalculator capitalGainsTaxCalculator,
                          @Nullable CapitalMarketAssumptionsProvider capitalMarketAssumptions) {
        this.federalTaxCalculator = federalTaxCalculator;
        this.capitalGainsTaxCalculator = capitalGainsTaxCalculator;
        this.capitalMarketAssumptions = capitalMarketAssumptions;
    }

    PoolStrategy build(List<ProjectionAccountInput> accounts, ScenarioParams params,
                       TaxCalculationStrategy taxStrategy, BigDecimal scenarioInflationRate,
                       int baseYear, boolean tentActive) {
        // Audit C10: geometricMeans(false) (the unchanged default) unless the scenario opts into
        // the extended 1928-2025 Depression-era window via params_json.include_depression_years.
        // A bond tent always projects on the default 1972-2025 window (bond maturity data starts in 1954, and
        // validation rejects the combination), so a legacy scenario with both never reaches the extended window.
        boolean depressionYears = paramsParser.includeDepressionYears(params, tentActive);
        Map<AssetClass, Double> geoMeans = capitalMarketAssumptions != null
                ? capitalMarketAssumptions.geometricMeans(depressionYears)
                : Map.of();
        // Override accounts must deflate at the SAME inflation rate income/SS/spending use — the
        // scenario's own rate — not a fixed CMA constant, or a nominal-override account would compound
        // in a different real-dollar frame than the rest of the projection. Fall back to the CMA
        // constant only if the scenario itself has no rate (resolveProjectionParams already defaults
        // it, so this fallback is effectively unreachable in practice).
        BigDecimal inflationRate = scenarioInflationRate != null ? scenarioInflationRate : CMA_INFLATION_RATE;
        var config = PoolStrategy.PoolConfig.builder(
                FilingStatus.resolve(params.filingStatus(), params.spouseBirthYear()),
                params.otherIncome() != null ? params.otherIncome() : BigDecimal.ZERO,
                params.annualRothConversion() != null ? params.annualRothConversion() : BigDecimal.ZERO,
                params.rothConversionStrategy(), params.targetBracketRate(),
                params.rothConversionStartYear(), params.resolvedWithdrawalOrder(), taxStrategy,
                params.dynamicSequencingBracketRate())
                .capitalMarketAssumptions(geoMeans, inflationRate)
                .capitalGainsTaxCalculator(capitalGainsTaxCalculator)
                .dividendYield(paramsParser.dividendYield(params))
                .interestYield(paramsParser.interestYield(params))
                .feeRate(paramsParser.feeRate(params))
                .baseYear(baseYear)
                .federalTaxCalculator(federalTaxCalculator)
                .earlyAccessAge(paramsParser.earlyAccessAge(params))
                .build();
        return PoolStrategy.create(accounts, config);
    }
}
