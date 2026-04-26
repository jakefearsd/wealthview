package com.wealthview.projection;

import com.wealthview.core.common.CompoundGrowth;
import com.wealthview.core.projection.dto.IncomeSourceType;
import com.wealthview.core.projection.dto.ProjectionIncomeSourceInput;
import com.wealthview.core.projection.dto.RentalPropertyYearDetail;
import com.wealthview.core.projection.tax.RentalLossCalculator;
import com.wealthview.core.projection.tax.SelfEmploymentTaxCalculator;
import com.wealthview.core.projection.tax.SocialSecurityTaxCalculator;
import static com.wealthview.core.common.Money.ROUNDING;
import static com.wealthview.core.common.Money.SCALE;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Processes income sources (rental properties, Social Security, part-time work, etc.)
 * for a single projection year, computing cash inflows, taxable income, and related fields.
 */
class IncomeSourceProcessor {

    private final RentalLossCalculator rentalLossCalculator;
    private final SocialSecurityTaxCalculator ssTaxCalculator;
    private final SelfEmploymentTaxCalculator seTaxCalculator;

    IncomeSourceProcessor(RentalLossCalculator rentalLossCalculator,
                          SocialSecurityTaxCalculator ssTaxCalculator,
                          SelfEmploymentTaxCalculator seTaxCalculator) {
        this.rentalLossCalculator = rentalLossCalculator;
        this.ssTaxCalculator = ssTaxCalculator;
        this.seTaxCalculator = seTaxCalculator;
    }

    record IncomeSourceYearResult(
            BigDecimal totalCashInflow,
            BigDecimal totalTaxableIncome,
            BigDecimal rentalIncomeGross,
            BigDecimal rentalExpensesTotal,
            BigDecimal depreciationTotal,
            BigDecimal rentalLossApplied,
            BigDecimal suspendedLossCarryforward,
            BigDecimal socialSecurityTaxable,
            BigDecimal selfEmploymentTax,
            Map<String, BigDecimal> incomeBySource,
            List<RentalPropertyYearDetail> rentalPropertyDetails
    ) {}

    /**
     * Sealed result hierarchy for per-income-type processing.
     * Each implementation carries the common fields (cashInflow, taxableIncome) plus
     * any type-specific fields that feed separate accumulators in the main loop.
     */
    private sealed interface IncomeTypeResult
            permits IncomeSourceProcessor.RentalResult,
                    IncomeSourceProcessor.SocialSecurityResult,
                    IncomeSourceProcessor.EmploymentResult,
                    IncomeSourceProcessor.DefaultResult {
        BigDecimal cashInflow();
        BigDecimal taxableIncome();
    }

    IncomeSourceYearResult process(
            List<ProjectionIncomeSourceInput> sources, int age, int yearsInRetirement,
            int taxYear, BigDecimal magi, String filingStatus, BigDecimal priorSuspendedLoss) {

        if (sources == null || sources.isEmpty()) {
            return new IncomeSourceYearResult(
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                    BigDecimal.ZERO, BigDecimal.ZERO, priorSuspendedLoss, BigDecimal.ZERO, BigDecimal.ZERO,
                    Map.of(), List.of());
        }

        BigDecimal totalCashInflow = BigDecimal.ZERO;
        BigDecimal totalTaxableIncome = BigDecimal.ZERO;
        BigDecimal rentalIncomeGross = BigDecimal.ZERO;
        BigDecimal rentalExpensesTotal = BigDecimal.ZERO;
        BigDecimal depreciationTotal = BigDecimal.ZERO;
        BigDecimal rentalLossApplied = BigDecimal.ZERO;
        BigDecimal suspendedLoss = priorSuspendedLoss;
        BigDecimal ssTaxable = BigDecimal.ZERO;
        BigDecimal seTax = BigDecimal.ZERO;
        Map<String, BigDecimal> incomeBySource = new HashMap<>();
        List<RentalPropertyYearDetail> rentalDetails = new ArrayList<>();

        // Collect non-SS income first (needed for SS provisional income calc)
        BigDecimal nonSSIncome = BigDecimal.ZERO;
        BigDecimal ssBenefit = BigDecimal.ZERO;

        for (var source : sources) {
            if (!ProjectionIncomeSourceInput.isActiveForAge(source, age)) {
                continue;
            }

            BigDecimal multiplier = transitionMultiplier(source, age);
            BigDecimal nominal = computeNominalAmount(source, yearsInRetirement)
                    .multiply(multiplier).setScale(SCALE, ROUNDING);
            if (source.incomeType() == IncomeSourceType.SOCIAL_SECURITY) {
                ssBenefit = ssBenefit.add(nominal);
            } else {
                nonSSIncome = nonSSIncome.add(nominal);
            }
        }

        for (var source : sources) {
            if (!ProjectionIncomeSourceInput.isActiveForAge(source, age)) {
                continue;
            }

            BigDecimal multiplier = transitionMultiplier(source, age);
            BigDecimal nominal = computeNominalAmount(source, yearsInRetirement)
                    .multiply(multiplier).setScale(SCALE, ROUNDING);

            String sourceKey = source.id().toString();
            var result = switch (source.incomeType()) {
                case RENTAL_PROPERTY -> processRentalIncome(source, nominal, taxYear, magi, suspendedLoss, multiplier);
                case SOCIAL_SECURITY -> processSocialSecurityIncome(nominal, nonSSIncome, magi, filingStatus);
                case PART_TIME_WORK  -> processEmploymentIncome(source, nominal, taxYear);
                default              -> processDefaultIncome(source, nominal);
            };

            totalCashInflow = totalCashInflow.add(result.cashInflow());
            totalTaxableIncome = totalTaxableIncome.add(result.taxableIncome());
            incomeBySource.merge(sourceKey, result.cashInflow(), BigDecimal::add);

            if (result instanceof RentalResult r) {
                rentalIncomeGross = rentalIncomeGross.add(nominal);
                rentalExpensesTotal = rentalExpensesTotal.add(r.expenses());
                depreciationTotal = depreciationTotal.add(r.depreciation());
                rentalLossApplied = rentalLossApplied.add(r.lossApplied());
                suspendedLoss = r.newSuspendedLoss();
                rentalDetails.add(new RentalPropertyYearDetail(
                        r.incomeSourceId(), r.propertyName(), r.taxTreatment(),
                        r.grossRent(), r.operatingExpenses(),
                        r.mortgageInterest(), r.propertyTax(),
                        r.depreciation(), r.taxableIncome(),
                        r.lossApplied(), r.newSuspendedLoss(),
                        r.newSuspendedLoss(),
                        r.cashInflow()));
            } else if (result instanceof SocialSecurityResult r) {
                ssTaxable = ssTaxable.add(r.ssTaxable());
            } else if (result instanceof EmploymentResult r) {
                seTax = seTax.add(r.seTax());
            }
        }

        return new IncomeSourceYearResult(
                totalCashInflow, totalTaxableIncome,
                rentalIncomeGross, rentalExpensesTotal, depreciationTotal,
                rentalLossApplied, suspendedLoss, ssTaxable, seTax,
                Map.copyOf(incomeBySource), List.copyOf(rentalDetails));
    }

    // --- Per-type result records ---

    private record RentalResult(
            BigDecimal cashInflow, BigDecimal taxableIncome, BigDecimal expenses,
            BigDecimal depreciation, BigDecimal lossApplied, BigDecimal newSuspendedLoss,
            UUID incomeSourceId, String propertyName, String taxTreatment,
            BigDecimal grossRent, BigDecimal mortgageInterest, BigDecimal propertyTax,
            BigDecimal operatingExpenses) implements IncomeTypeResult {
    }

    private record SocialSecurityResult(
            BigDecimal cashInflow, BigDecimal taxableIncome, BigDecimal ssTaxable)
            implements IncomeTypeResult {
    }

    private record EmploymentResult(
            BigDecimal cashInflow, BigDecimal taxableIncome, BigDecimal seTax)
            implements IncomeTypeResult {
    }

    private record DefaultResult(
            BigDecimal cashInflow, BigDecimal taxableIncome) implements IncomeTypeResult {
    }

    // --- Per-type processing methods ---

    private RentalResult processRentalIncome(
            ProjectionIncomeSourceInput source, BigDecimal nominal,
            int taxYear, BigDecimal magi, BigDecimal suspendedLoss,
            BigDecimal transitionMultiplier) {

        BigDecimal opExp = source.annualOperatingExpenses() != null
                ? source.annualOperatingExpenses() : BigDecimal.ZERO;
        BigDecimal mortInt = source.annualMortgageInterest() != null
                ? source.annualMortgageInterest() : BigDecimal.ZERO;
        BigDecimal propTax = source.annualPropertyTax() != null
                ? source.annualPropertyTax() : BigDecimal.ZERO;
        BigDecimal expenses = opExp.add(mortInt).add(propTax)
                .multiply(transitionMultiplier).setScale(SCALE, ROUNDING);

        BigDecimal depreciation = BigDecimal.ZERO;
        if (source.depreciationByYear() != null && source.depreciationMethod() != null
                && !"none".equals(source.depreciationMethod())) {
            depreciation = source.depreciationByYear()
                    .getOrDefault(taxYear, BigDecimal.ZERO);
        }

        // Principal reduces cash flow but is NOT tax-deductible
        BigDecimal mortPrincipal = source.annualMortgagePrincipal() != null
                ? source.annualMortgagePrincipal() : BigDecimal.ZERO;
        BigDecimal principalScaled = mortPrincipal
                .multiply(transitionMultiplier).setScale(SCALE, ROUNDING);

        BigDecimal cashFlow = nominal.subtract(expenses).subtract(principalScaled);
        BigDecimal netTaxable = nominal.subtract(expenses).subtract(depreciation);

        var lossResult = rentalLossCalculator.applyLossRules(
                netTaxable, source.taxTreatment(),
                BigDecimal.ZERO, magi, suspendedLoss);

        BigDecimal scaledOpExp = opExp.multiply(transitionMultiplier).setScale(SCALE, ROUNDING);
        BigDecimal scaledMortInt = mortInt.multiply(transitionMultiplier).setScale(SCALE, ROUNDING);
        BigDecimal scaledPropTax = propTax.multiply(transitionMultiplier).setScale(SCALE, ROUNDING);

        return new RentalResult(cashFlow, lossResult.netTaxableIncome(), expenses,
                depreciation, lossResult.lossAppliedToIncome(), lossResult.lossSuspended(),
                source.id(), source.name(), source.taxTreatment(),
                nominal, scaledMortInt, scaledPropTax, scaledOpExp);
    }

    private SocialSecurityResult processSocialSecurityIncome(
            BigDecimal nominal,
            BigDecimal nonSSIncome, BigDecimal magi, String filingStatus) {

        var taxableAmount = ssTaxCalculator.computeTaxableAmount(
                nominal,
                nonSSIncome.add(magi),
                "married_filing_jointly".equals(filingStatus) ? "married_filing_jointly" : "single");
        return new SocialSecurityResult(nominal, taxableAmount, taxableAmount);
    }

    private EmploymentResult processEmploymentIncome(
            ProjectionIncomeSourceInput source, BigDecimal nominal, int taxYear) {

        if ("self_employment".equals(source.taxTreatment())) {
            var tax = seTaxCalculator.computeSETax(nominal, taxYear);
            // IRS allows deducting 50% of SE tax from gross income (Schedule 1, line 15)
            BigDecimal seDeduction = seTaxCalculator.deductibleAmount(tax);
            BigDecimal taxableIncome = nominal.subtract(seDeduction);
            return new EmploymentResult(nominal, taxableIncome, tax);
        }
        return new EmploymentResult(nominal, nominal, BigDecimal.ZERO);
    }

    private DefaultResult processDefaultIncome(
            ProjectionIncomeSourceInput source, BigDecimal nominal) {
        // pension, annuity, other — fully taxable unless tax_free
        BigDecimal taxableIncome = "tax_free".equals(source.taxTreatment())
                ? BigDecimal.ZERO
                : nominal;
        return new DefaultResult(nominal, taxableIncome);
    }

    // --- Utility methods ---

    private BigDecimal transitionMultiplier(ProjectionIncomeSourceInput source, int age) {
        if (source.oneTime()) {
            return BigDecimal.ONE;
        }
        if (age == source.startAge()
                || (source.endAge() != null && age == source.endAge())) {
            return new BigDecimal("0.5");
        }
        return BigDecimal.ONE;
    }

    BigDecimal computeNominalAmount(ProjectionIncomeSourceInput source, int yearsInRetirement) {
        if (source.oneTime() || yearsInRetirement <= 1
                || source.inflationRate().compareTo(BigDecimal.ZERO) == 0) {
            return source.annualAmount();
        }
        return CompoundGrowth.inflate(source.annualAmount(), source.inflationRate(), yearsInRetirement - 1)
                .setScale(SCALE, ROUNDING);
    }
}
