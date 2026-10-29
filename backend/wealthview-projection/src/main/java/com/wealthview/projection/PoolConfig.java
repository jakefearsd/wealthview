package com.wealthview.projection;

import java.math.BigDecimal;
import java.util.Map;

import com.wealthview.core.projection.dto.AssetClass;
import com.wealthview.core.projection.household.AgeMilestones;
import com.wealthview.core.projection.strategy.WithdrawalOrder;
import com.wealthview.core.projection.tax.CapitalGainsTaxCalculator;
import com.wealthview.core.projection.tax.FederalTaxCalculator;
import com.wealthview.core.projection.tax.FilingStatus;
import com.wealthview.core.projection.tax.TaxCalculationStrategy;

/** Configuration record encapsulating multi-pool construction parameters. */
record PoolConfig(
        FilingStatus filingStatus,
        BigDecimal otherIncome,
        BigDecimal annualRothConversion,
        String rothConversionStrategy,
        BigDecimal targetBracketRate,
        Integer rothConversionStartYear,
        WithdrawalOrder withdrawalOrder,
        TaxCalculationStrategy taxCalculator,
        BigDecimal dynamicSequencingBracketRate,
        Map<AssetClass, Double> geoMeans,
        BigDecimal inflationRate,
        CapitalGainsTaxCalculator capitalGainsTaxCalculator,
        BigDecimal dividendYield,
        BigDecimal interestYield,
        BigDecimal feeRate,
        int baseYear,
        FederalTaxCalculator federalTaxCalculator,
        // Phase 1a: first penalty-free age (59 when born Jan-Jun, else 60). See AgeMilestones.
        int earlyAccessAge) {

    /**
     * Back-compat constructor for callers that predate the early-access age (Phase 1a): supplies
     * the legacy whole-year age (60), so the penalty and dynamic-sequencing rules behave exactly
     * as before.
     */
    PoolConfig(FilingStatus filingStatus, BigDecimal otherIncome, BigDecimal annualRothConversion,
               String rothConversionStrategy, BigDecimal targetBracketRate,
               Integer rothConversionStartYear, WithdrawalOrder withdrawalOrder,
               TaxCalculationStrategy taxCalculator, BigDecimal dynamicSequencingBracketRate,
               Map<AssetClass, Double> geoMeans, BigDecimal inflationRate,
               CapitalGainsTaxCalculator capitalGainsTaxCalculator, BigDecimal dividendYield,
               BigDecimal interestYield, BigDecimal feeRate, int baseYear,
               FederalTaxCalculator federalTaxCalculator) {
        this(filingStatus, otherIncome, annualRothConversion, rothConversionStrategy, targetBracketRate,
                rothConversionStartYear, withdrawalOrder, taxCalculator, dynamicSequencingBracketRate,
                geoMeans, inflationRate, capitalGainsTaxCalculator, dividendYield, interestYield, feeRate,
                baseYear, federalTaxCalculator, AgeMilestones.LEGACY_EARLY_ACCESS_AGE);
    }

    /**
     * Back-compat constructor for callers that predate allocation-driven returns: uses an
     * empty capital-market map and zero inflation, so returns come solely from per-account
     * overrides (the legacy {@code expectedReturn} path). No capital-gains calculator and a
     * zero dividend yield, so the taxable pool tracks cost basis but realizes no LTCG tax and
     * no dividend drag — the pre-lots scalar behavior, bit-for-bit. Zero fee rate for the same
     * reason (audit B1) — these legacy callers stay fee-free. Zero interest yield for the same
     * reason (audit C1) — these legacy callers realize no bond-sleeve interest either.
     */
    PoolConfig(FilingStatus filingStatus, BigDecimal otherIncome, BigDecimal annualRothConversion,
               String rothConversionStrategy, BigDecimal targetBracketRate,
               Integer rothConversionStartYear, WithdrawalOrder withdrawalOrder,
               TaxCalculationStrategy taxCalculator, BigDecimal dynamicSequencingBracketRate) {
        this(filingStatus, otherIncome, annualRothConversion, rothConversionStrategy, targetBracketRate,
                rothConversionStartYear, withdrawalOrder, taxCalculator, dynamicSequencingBracketRate,
                Map.of(), BigDecimal.ZERO, null, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 0, null,
                AgeMilestones.LEGACY_EARLY_ACCESS_AGE);
    }

    /**
     * Back-compat constructor for allocation-driven callers that predate capital-gains taxation:
     * supplies capital-market means and inflation but no LTCG calculator / dividend yield /
     * interest yield / fee rate / federal standard-deduction source.
     */
    PoolConfig(FilingStatus filingStatus, BigDecimal otherIncome, BigDecimal annualRothConversion,
               String rothConversionStrategy, BigDecimal targetBracketRate,
               Integer rothConversionStartYear, WithdrawalOrder withdrawalOrder,
               TaxCalculationStrategy taxCalculator, BigDecimal dynamicSequencingBracketRate,
               Map<AssetClass, Double> geoMeans, BigDecimal inflationRate) {
        this(filingStatus, otherIncome, annualRothConversion, rothConversionStrategy, targetBracketRate,
                rothConversionStartYear, withdrawalOrder, taxCalculator, dynamicSequencingBracketRate,
                geoMeans, inflationRate, null, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 0, null,
                AgeMilestones.LEGACY_EARLY_ACCESS_AGE);
    }

    /**
     * Entry point for building a config fluently, mirroring {@code
     * TrialSimulator.SimulationConfig.Builder}'s pattern. The nine arguments are the
     * tax/withdrawal-strategy core every pool needs (the same nine the smallest back-compat
     * constructor above takes); the allocation-driven and capital-gains-taxation knobs default to
     * the documented byte-identical no-op values the back-compat constructors already encode
     * (empty capital-market map, zero inflation/dividend/interest/fee, no LTCG or federal-deduction
     * calculator) so a builder chain only names the mechanisms a caller actually uses.
     */
    static Builder builder(FilingStatus filingStatus, BigDecimal otherIncome, BigDecimal annualRothConversion,
                           String rothConversionStrategy, BigDecimal targetBracketRate,
                           Integer rothConversionStartYear, WithdrawalOrder withdrawalOrder,
                           TaxCalculationStrategy taxCalculator, BigDecimal dynamicSequencingBracketRate) {
        return new Builder(filingStatus, otherIncome, annualRothConversion, rothConversionStrategy,
                targetBracketRate, rothConversionStartYear, withdrawalOrder, taxCalculator,
                dynamicSequencingBracketRate);
    }

    /** Fluent builder. Defaults are the same no-op anchors the back-compat constructors above
     * document: empty capital-market map, zero inflation, no LTCG calculator, zero dividend/
     * interest/fee, base year 0, no federal-deduction source, early-access age 60. */
    static final class Builder {
        private final FilingStatus filingStatus;
        private final BigDecimal otherIncome;
        private final BigDecimal annualRothConversion;
        private final String rothConversionStrategy;
        private final BigDecimal targetBracketRate;
        private final Integer rothConversionStartYear;
        private final WithdrawalOrder withdrawalOrder;
        private final TaxCalculationStrategy taxCalculator;
        private final BigDecimal dynamicSequencingBracketRate;
        private Map<AssetClass, Double> geoMeans = Map.of();
        private BigDecimal inflationRate = BigDecimal.ZERO;
        private CapitalGainsTaxCalculator capitalGainsTaxCalculator;
        private BigDecimal dividendYield = BigDecimal.ZERO;
        private BigDecimal interestYield = BigDecimal.ZERO;
        private BigDecimal feeRate = BigDecimal.ZERO;
        private int baseYear;
        private FederalTaxCalculator federalTaxCalculator;
        private int earlyAccessAge = AgeMilestones.LEGACY_EARLY_ACCESS_AGE;

        private Builder(FilingStatus filingStatus, BigDecimal otherIncome, BigDecimal annualRothConversion,
                        String rothConversionStrategy, BigDecimal targetBracketRate,
                        Integer rothConversionStartYear, WithdrawalOrder withdrawalOrder,
                        TaxCalculationStrategy taxCalculator, BigDecimal dynamicSequencingBracketRate) {
            this.filingStatus = filingStatus;
            this.otherIncome = otherIncome;
            this.annualRothConversion = annualRothConversion;
            this.rothConversionStrategy = rothConversionStrategy;
            this.targetBracketRate = targetBracketRate;
            this.rothConversionStartYear = rothConversionStartYear;
            this.withdrawalOrder = withdrawalOrder;
            this.taxCalculator = taxCalculator;
            this.dynamicSequencingBracketRate = dynamicSequencingBracketRate;
        }

        Builder capitalMarketAssumptions(Map<AssetClass, Double> geoMeans, BigDecimal inflationRate) {
            this.geoMeans = geoMeans;
            this.inflationRate = inflationRate;
            return this;
        }

        Builder capitalGainsTaxCalculator(CapitalGainsTaxCalculator capitalGainsTaxCalculator) {
            this.capitalGainsTaxCalculator = capitalGainsTaxCalculator;
            return this;
        }

        Builder dividendYield(BigDecimal dividendYield) {
            this.dividendYield = dividendYield;
            return this;
        }

        Builder interestYield(BigDecimal interestYield) {
            this.interestYield = interestYield;
            return this;
        }

        Builder feeRate(BigDecimal feeRate) {
            this.feeRate = feeRate;
            return this;
        }

        Builder baseYear(int baseYear) {
            this.baseYear = baseYear;
            return this;
        }

        Builder federalTaxCalculator(FederalTaxCalculator federalTaxCalculator) {
            this.federalTaxCalculator = federalTaxCalculator;
            return this;
        }

        Builder earlyAccessAge(int earlyAccessAge) {
            this.earlyAccessAge = earlyAccessAge;
            return this;
        }

        PoolConfig build() {
            return new PoolConfig(filingStatus, otherIncome, annualRothConversion, rothConversionStrategy,
                    targetBracketRate, rothConversionStartYear, withdrawalOrder, taxCalculator,
                    dynamicSequencingBracketRate, geoMeans, inflationRate, capitalGainsTaxCalculator,
                    dividendYield, interestYield, feeRate, baseYear, federalTaxCalculator, earlyAccessAge);
        }
    }
}
