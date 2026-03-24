package com.wealthview.projection;

import com.wealthview.core.projection.dto.IncomeSourceType;
import com.wealthview.core.projection.dto.ProjectionIncomeSourceInput;
import com.wealthview.core.projection.dto.RentalPropertyYearDetail;
import com.wealthview.core.projection.tax.RentalLossCalculator;
import com.wealthview.core.projection.tax.SelfEmploymentTaxCalculator;
import com.wealthview.core.projection.tax.SocialSecurityTaxCalculator;

import java.math.BigDecimal;
import java.math.RoundingMode;
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

    private static final int SCALE = 4;
    private static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

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
            if (!isActiveForAge(source, age)) {
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
            if (!isActiveForAge(source, age)) {
                continue;
            }

            BigDecimal multiplier = transitionMultiplier(source, age);
            BigDecimal nominal = computeNominalAmount(source, yearsInRetirement)
                    .multiply(multiplier).setScale(SCALE, ROUNDING);

            String sourceKey = source.id().toString();
            switch (source.incomeType()) {
                case RENTAL_PROPERTY -> {
                    var rental = processRentalProperty(source, nominal, taxYear, magi, suspendedLoss, multiplier);
                    rentalIncomeGross = rentalIncomeGross.add(nominal);
                    rentalExpensesTotal = rentalExpensesTotal.add(rental.expenses());
                    depreciationTotal = depreciationTotal.add(rental.depreciation());
                    totalCashInflow = totalCashInflow.add(rental.cashFlow());
                    rentalLossApplied = rentalLossApplied.add(rental.lossApplied());
                    suspendedLoss = rental.newSuspendedLoss();
                    totalTaxableIncome = totalTaxableIncome.add(rental.taxableIncome());
                    incomeBySource.merge(sourceKey, rental.cashFlow(), BigDecimal::add);
                    rentalDetails.add(new RentalPropertyYearDetail(
                            rental.incomeSourceId(), rental.propertyName(), rental.taxTreatment(),
                            rental.grossRent(), rental.operatingExpenses(),
                            rental.mortgageInterest(), rental.propertyTax(),
                            rental.depreciation(), rental.taxableIncome(),
                            rental.lossApplied(), rental.newSuspendedLoss(),
                            rental.newSuspendedLoss(),
                            rental.cashFlow()));
                }
                case SOCIAL_SECURITY -> {
                    totalCashInflow = totalCashInflow.add(nominal);
                    var taxableAmount = ssTaxCalculator.computeTaxableAmount(
                            nominal,
                            nonSSIncome.add(magi),
                            "married_filing_jointly".equals(filingStatus) ? "married_filing_jointly" : "single");
                    ssTaxable = ssTaxable.add(taxableAmount);
                    totalTaxableIncome = totalTaxableIncome.add(taxableAmount);
                    incomeBySource.merge(sourceKey, nominal, BigDecimal::add);
                }
                case PART_TIME_WORK -> {
                    totalCashInflow = totalCashInflow.add(nominal);
                    if ("self_employment".equals(source.taxTreatment())) {
                        var tax = seTaxCalculator.computeSETax(nominal, taxYear);
                        seTax = seTax.add(tax);
                        // IRS allows deducting 50% of SE tax from gross income (Schedule 1, line 15)
                        BigDecimal seDeduction = seTaxCalculator.deductibleAmount(tax);
                        totalTaxableIncome = totalTaxableIncome.add(nominal).subtract(seDeduction);
                    } else {
                        totalTaxableIncome = totalTaxableIncome.add(nominal);
                    }
                    incomeBySource.merge(sourceKey, nominal, BigDecimal::add);
                }
                default -> {
                    // pension, annuity, other — fully taxable unless tax_free
                    totalCashInflow = totalCashInflow.add(nominal);
                    if (!"tax_free".equals(source.taxTreatment())) {
                        totalTaxableIncome = totalTaxableIncome.add(nominal);
                    }
                    incomeBySource.merge(sourceKey, nominal, BigDecimal::add);
                }
            }
        }

        return new IncomeSourceYearResult(
                totalCashInflow, totalTaxableIncome,
                rentalIncomeGross, rentalExpensesTotal, depreciationTotal,
                rentalLossApplied, suspendedLoss, ssTaxable, seTax,
                Map.copyOf(incomeBySource), List.copyOf(rentalDetails));
    }

    private record RentalResult(
            BigDecimal cashFlow, BigDecimal taxableIncome, BigDecimal expenses,
            BigDecimal depreciation, BigDecimal lossApplied, BigDecimal newSuspendedLoss,
            UUID incomeSourceId, String propertyName, String taxTreatment,
            BigDecimal grossRent, BigDecimal mortgageInterest, BigDecimal propertyTax,
            BigDecimal operatingExpenses) {
    }

    private RentalResult processRentalProperty(
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

    boolean isActiveForAge(ProjectionIncomeSourceInput source, int age) {
        if (source.oneTime()) {
            return age == source.startAge();
        }
        if (age < source.startAge()) {
            return false;
        }
        return source.endAge() == null || age <= source.endAge();
    }

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
        BigDecimal factor = BigDecimal.ONE.add(source.inflationRate()).pow(yearsInRetirement - 1);
        return source.annualAmount().multiply(factor).setScale(SCALE, ROUNDING);
    }
}
