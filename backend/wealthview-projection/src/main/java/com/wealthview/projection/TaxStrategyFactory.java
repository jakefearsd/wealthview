package com.wealthview.projection;

import java.math.BigDecimal;

import org.springframework.lang.Nullable;

import com.wealthview.core.projection.dto.ScenarioParams;
import com.wealthview.core.projection.household.HouseholdContext;
import com.wealthview.core.projection.tax.CombinedTaxCalculator;
import com.wealthview.core.projection.tax.FederalOnlyTaxStrategy;
import com.wealthview.core.projection.tax.FederalTaxCalculator;
import com.wealthview.core.projection.tax.NullStateTaxCalculator;
import com.wealthview.core.projection.tax.StateTaxCalculatorFactory;
import com.wealthview.core.projection.tax.TaxCalculationStrategy;

/**
 * Builds the {@link TaxCalculationStrategy} appropriate for a scenario's filing state
 * and primary-residence deductions. Extracted from {@link DeterministicProjectionEngine}
 * to isolate tax-strategy assembly.
 */
final class TaxStrategyFactory {

    @Nullable
    private final FederalTaxCalculator taxCalculator;
    @Nullable
    private final StateTaxCalculatorFactory stateTaxCalculatorFactory;

    TaxStrategyFactory(@Nullable FederalTaxCalculator taxCalculator,
                       @Nullable StateTaxCalculatorFactory stateTaxCalculatorFactory) {
        this.taxCalculator = taxCalculator;
        this.stateTaxCalculatorFactory = stateTaxCalculatorFactory;
    }

    /**
     * Builds the {@link TaxCalculationStrategy} for the scenario's filing status, state, primary
     * residence deductions, and (household task 7) per-person age-65 threading.
     *
     * @param household household task 7: threaded into the built strategy for the per-person age-65
     *     deduction (spec §4 step 6) -- {@code null} reproduces the pre-household behavior exactly
     *     (the {@code birthYear}-only single-age adder).
     */
    @Nullable
    TaxCalculationStrategy buildTaxStrategy(ScenarioParams params, @Nullable HouseholdContext household) {
        if (taxCalculator == null) {
            return null;
        }

        BigDecimal propertyTax = params.primaryResidencePropertyTax() != null
                ? params.primaryResidencePropertyTax() : BigDecimal.ZERO;
        BigDecimal mortgageInterest = params.primaryResidenceMortgageInterest() != null
                ? params.primaryResidenceMortgageInterest() : BigDecimal.ZERO;
        // Audit D: threaded through so the age-65+ additional standard deduction applies
        // automatically once the primary filer turns 65, for every tax year this strategy computes.
        Integer birthYear = params.birthYear();

        if (params.state() != null && !params.state().isBlank() && stateTaxCalculatorFactory != null) {
            var stateCalc = stateTaxCalculatorFactory.forState(params.state());
            return new CombinedTaxCalculator(
                    taxCalculator, stateCalc, propertyTax, mortgageInterest, birthYear, household);
        }

        // Even without state tax, primary residence deductions may exceed the standard
        // deduction (e.g., a Texan with a large mortgage). Use CombinedTaxCalculator with
        // NullStateTaxCalculator so itemized vs standard comparison still happens.
        if (propertyTax.compareTo(BigDecimal.ZERO) > 0 || mortgageInterest.compareTo(BigDecimal.ZERO) > 0) {
            return new CombinedTaxCalculator(taxCalculator, new NullStateTaxCalculator(),
                    propertyTax, mortgageInterest, birthYear, household);
        }

        return new FederalOnlyTaxStrategy(taxCalculator, birthYear, household);
    }
}
