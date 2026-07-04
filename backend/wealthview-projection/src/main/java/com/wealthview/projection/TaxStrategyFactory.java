package com.wealthview.projection;

import java.math.BigDecimal;

import org.springframework.lang.Nullable;

import com.wealthview.core.projection.dto.ScenarioParams;
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

    @Nullable
    TaxCalculationStrategy buildTaxStrategy(ScenarioParams params) {
        if (taxCalculator == null) {
            return null;
        }

        BigDecimal propertyTax = params.primaryResidencePropertyTax() != null
                ? params.primaryResidencePropertyTax() : BigDecimal.ZERO;
        BigDecimal mortgageInterest = params.primaryResidenceMortgageInterest() != null
                ? params.primaryResidenceMortgageInterest() : BigDecimal.ZERO;

        if (params.state() != null && !params.state().isBlank() && stateTaxCalculatorFactory != null) {
            var stateCalc = stateTaxCalculatorFactory.forState(params.state());
            return new CombinedTaxCalculator(taxCalculator, stateCalc, propertyTax, mortgageInterest);
        }

        // Even without state tax, primary residence deductions may exceed the standard
        // deduction (e.g., a Texan with a large mortgage). Use CombinedTaxCalculator with
        // NullStateTaxCalculator so itemized vs standard comparison still happens.
        if (propertyTax.compareTo(BigDecimal.ZERO) > 0 || mortgageInterest.compareTo(BigDecimal.ZERO) > 0) {
            return new CombinedTaxCalculator(taxCalculator, new NullStateTaxCalculator(),
                    propertyTax, mortgageInterest);
        }

        return new FederalOnlyTaxStrategy(taxCalculator);
    }
}
