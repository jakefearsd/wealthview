package com.wealthview.projection;

import com.wealthview.core.projection.dto.ProjectionIncomeSourceInput;
import com.wealthview.core.projection.tax.RentalLossCalculator;
import com.wealthview.core.projection.tax.SelfEmploymentTaxCalculator;
import com.wealthview.core.projection.tax.SocialSecurityTaxCalculator;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

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
            BigDecimal selfEmploymentTax
    ) {}

    IncomeSourceYearResult process(
            List<ProjectionIncomeSourceInput> sources, int age, int yearsInRetirement,
            int taxYear, BigDecimal magi, String filingStatus, BigDecimal priorSuspendedLoss) {

        if (sources == null || sources.isEmpty()) {
            return new IncomeSourceYearResult(
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                    BigDecimal.ZERO, BigDecimal.ZERO, priorSuspendedLoss, BigDecimal.ZERO, BigDecimal.ZERO);
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

        // Collect non-SS income first (needed for SS provisional income calc)
        BigDecimal nonSSIncome = BigDecimal.ZERO;
        BigDecimal ssBenefit = BigDecimal.ZERO;

        for (var source : sources) {
            if (!isActiveForAge(source, age)) continue;

            BigDecimal nominal = computeNominalAmount(source, yearsInRetirement);
            if ("social_security".equals(source.incomeType())) {
                ssBenefit = ssBenefit.add(nominal);
            } else {
                nonSSIncome = nonSSIncome.add(nominal);
            }
        }

        for (var source : sources) {
            if (!isActiveForAge(source, age)) continue;

            BigDecimal nominal = computeNominalAmount(source, yearsInRetirement);

            switch (source.incomeType()) {
                case "rental_property" -> {
                    var rental = processRentalProperty(source, nominal, taxYear, magi, suspendedLoss);
                    rentalIncomeGross = rentalIncomeGross.add(nominal);
                    rentalExpensesTotal = rentalExpensesTotal.add(rental.expenses());
                    depreciationTotal = depreciationTotal.add(rental.depreciation());
                    totalCashInflow = totalCashInflow.add(rental.cashFlow());
                    rentalLossApplied = rentalLossApplied.add(rental.lossApplied());
                    suspendedLoss = rental.newSuspendedLoss();
                    totalTaxableIncome = totalTaxableIncome.add(rental.taxableIncome());
                }
                case "social_security" -> {
                    totalCashInflow = totalCashInflow.add(nominal);
                    var taxableAmount = ssTaxCalculator.computeTaxableAmount(
                            nominal,
                            nonSSIncome.add(magi),
                            "married_filing_jointly".equals(filingStatus) ? "married_filing_jointly" : "single");
                    ssTaxable = ssTaxable.add(taxableAmount);
                    totalTaxableIncome = totalTaxableIncome.add(taxableAmount);
                }
                case "part_time_work" -> {
                    totalCashInflow = totalCashInflow.add(nominal);
                    if ("self_employment".equals(source.taxTreatment())) {
                        var tax = seTaxCalculator.computeSETax(nominal, taxYear);
                        seTax = seTax.add(tax);
                        totalTaxableIncome = totalTaxableIncome.add(nominal);
                    } else {
                        totalTaxableIncome = totalTaxableIncome.add(nominal);
                    }
                }
                default -> {
                    // pension, annuity, other — fully taxable unless tax_free
                    totalCashInflow = totalCashInflow.add(nominal);
                    if (!"tax_free".equals(source.taxTreatment())) {
                        totalTaxableIncome = totalTaxableIncome.add(nominal);
                    }
                }
            }
        }

        return new IncomeSourceYearResult(
                totalCashInflow, totalTaxableIncome,
                rentalIncomeGross, rentalExpensesTotal, depreciationTotal,
                rentalLossApplied, suspendedLoss, ssTaxable, seTax);
    }

    private record RentalResult(
            BigDecimal cashFlow, BigDecimal taxableIncome, BigDecimal expenses,
            BigDecimal depreciation, BigDecimal lossApplied, BigDecimal newSuspendedLoss) {
    }

    private RentalResult processRentalProperty(
            ProjectionIncomeSourceInput source, BigDecimal nominal,
            int taxYear, BigDecimal magi, BigDecimal suspendedLoss) {

        BigDecimal opExp = source.annualOperatingExpenses() != null
                ? source.annualOperatingExpenses() : BigDecimal.ZERO;
        BigDecimal mortInt = source.annualMortgageInterest() != null
                ? source.annualMortgageInterest() : BigDecimal.ZERO;
        BigDecimal propTax = source.annualPropertyTax() != null
                ? source.annualPropertyTax() : BigDecimal.ZERO;
        BigDecimal expenses = opExp.add(mortInt).add(propTax);

        BigDecimal depreciation = BigDecimal.ZERO;
        if (source.depreciationByYear() != null && source.depreciationMethod() != null
                && !"none".equals(source.depreciationMethod())) {
            depreciation = source.depreciationByYear()
                    .getOrDefault(taxYear, BigDecimal.ZERO);
        }

        BigDecimal cashFlow = nominal.subtract(expenses);
        BigDecimal netTaxable = nominal.subtract(expenses).subtract(depreciation);

        var lossResult = rentalLossCalculator.applyLossRules(
                netTaxable, source.taxTreatment(),
                BigDecimal.ZERO, magi, suspendedLoss);

        return new RentalResult(cashFlow, lossResult.netTaxableIncome(), expenses,
                depreciation, lossResult.lossAppliedToIncome(), lossResult.lossSuspended());
    }

    boolean isActiveForAge(ProjectionIncomeSourceInput source, int age) {
        if (age < source.startAge()) return false;
        if (source.endAge() != null && age >= source.endAge()) return false;
        return true;
    }

    BigDecimal computeNominalAmount(ProjectionIncomeSourceInput source, int yearsInRetirement) {
        if (yearsInRetirement <= 1 || source.inflationRate().compareTo(BigDecimal.ZERO) == 0) {
            return source.annualAmount();
        }
        BigDecimal factor = BigDecimal.ONE.add(source.inflationRate()).pow(yearsInRetirement - 1);
        return source.annualAmount().multiply(factor).setScale(SCALE, ROUNDING);
    }
}
