package com.wealthview.projection;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.lang.Nullable;

import com.wealthview.core.projection.dto.IncomeSourceType;
import com.wealthview.core.projection.dto.ProjectionIncomeSourceInput;
import com.wealthview.core.projection.dto.RentalPropertyYearDetail;
import com.wealthview.core.projection.household.HouseholdContext;
import com.wealthview.core.projection.tax.FilingStatus;
import com.wealthview.core.projection.tax.RentalLossCalculator;
import com.wealthview.core.projection.tax.SelfEmploymentTaxCalculator;
import com.wealthview.core.projection.tax.SocialSecurityTaxCalculator;

import static com.wealthview.core.common.Money.ROUNDING;
import static com.wealthview.core.common.Money.SCALE;

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

    /**
     * {@code netRentalTaxableIncome} (T18a-3) is the year's AGGREGATE net taxable rental income
     * across all rental sources (post passive-loss-rule treatment via {@link RentalLossCalculator}
     * -- the same per-source figure already folded into {@code totalTaxableIncome}, broken out
     * separately here so callers can thread it into the NIIT Net Investment Income base without
     * re-deriving it from {@code rentalPropertyDetails}). May be negative when a net rental LOSS
     * was allowed against non-passive income. Zero when no rental sources are active.
     */
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
            List<RentalPropertyYearDetail> rentalPropertyDetails,
            BigDecimal netRentalTaxableIncome
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
            List<ProjectionIncomeSourceInput> sources, int age, int yearsFromBase,
            int taxYear, BigDecimal magi, FilingStatus filingStatus, BigDecimal priorSuspendedLoss,
            BigDecimal scenarioInflationRate, int baseYear) {
        return process(sources, age, yearsFromBase, taxYear, magi, filingStatus,
                priorSuspendedLoss, scenarioInflationRate, baseYear, BigDecimal.ZERO, null);
    }

    /**
     * Processes income sources for one projection year.
     *
     * @param yearsFromBase a 1-INDEXED count of calendar years since the projection's base year
     *     (audit C7: {@code (taxYear - baseYear) + 1}, floored by the caller so the base year and
     *     any earlier year are 1) — NOT years since retirement. Feeds
     *     {@link IncomeYearMath#realAmount}'s income-deflation clock (same 1-indexed shape the
     *     pre-C7 {@code yearsInRetirement} parameter used) so a fixed-nominal source erodes
     *     correctly across BOTH accumulation and retirement years, matching the Social Security
     *     threshold deflator's own {@code taxYear - baseYear} anchor computed just below.
     * @param additionalProvisionalIncome ordinary income realized elsewhere this year (traditional
     *     withdrawals + RMD excess + Roth conversion + realized LTCG/dividends) that belongs in the
     *     Social Security provisional-income base (audit B2). The deterministic engine converges this
     *     to the actual realized portfolio income across a two-pass fixed-point loop; callers that
     *     don't participate pass {@link BigDecimal#ZERO} (the 9-arg overload), preserving prior
     *     behavior. All Social Security sources are aggregated into ONE combined provisional-income
     *     computation, so MFJ spousal half-benefits are summed rather than evaluated per source
     *     (audit T3-1).
     */
    IncomeSourceYearResult process(
            List<ProjectionIncomeSourceInput> sources, int age, int yearsFromBase,
            int taxYear, BigDecimal magi, FilingStatus filingStatus, BigDecimal priorSuspendedLoss,
            BigDecimal scenarioInflationRate, int baseYear, BigDecimal additionalProvisionalIncome) {
        return process(sources, age, yearsFromBase, taxYear, magi, filingStatus, priorSuspendedLoss,
                scenarioInflationRate, baseYear, additionalProvisionalIncome, null);
    }

    /**
     * Household task 7 (T5-review, spec §1): like the 10-arg overload, but when {@code household} is
     * known, each source's {@code start_age}/{@code end_age} window (and boundary-year 0.5 proration)
     * is evaluated against ITS OWNER's age in {@code taxYear} rather than the uniform {@code age}
     * (the primary's, threaded from the engine's single per-year age variable) -- a spouse-owned
     * source now starts/ends at the SPOUSE's age, not the primary's, while both are alive. {@code
     * household} {@code null} (every pre-household call site, via the 9- and 10-arg overloads above)
     * reproduces the age-uniform behavior byte-for-byte. See {@link IncomeYearMath#resolveSourceAge}.
     */
    IncomeSourceYearResult process(
            List<ProjectionIncomeSourceInput> sources, int age, int yearsFromBase,
            int taxYear, BigDecimal magi, FilingStatus filingStatus, BigDecimal priorSuspendedLoss,
            BigDecimal scenarioInflationRate, int baseYear, BigDecimal additionalProvisionalIncome,
            @Nullable HouseholdContext household) {

        if (sources == null || sources.isEmpty()) {
            return new IncomeSourceYearResult(
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                    BigDecimal.ZERO, BigDecimal.ZERO, priorSuspendedLoss, BigDecimal.ZERO, BigDecimal.ZERO,
                    Map.of(), List.of(), BigDecimal.ZERO);
        }

        BigDecimal totalCashInflow = BigDecimal.ZERO;
        BigDecimal totalTaxableIncome = BigDecimal.ZERO;
        BigDecimal rentalIncomeGross = BigDecimal.ZERO;
        BigDecimal rentalExpensesTotal = BigDecimal.ZERO;
        BigDecimal depreciationTotal = BigDecimal.ZERO;
        BigDecimal rentalLossApplied = BigDecimal.ZERO;
        BigDecimal netRentalTaxableIncome = BigDecimal.ZERO;
        BigDecimal suspendedLoss = priorSuspendedLoss;
        BigDecimal seTax = BigDecimal.ZERO;
        Map<String, BigDecimal> incomeBySource = new HashMap<>();
        List<RentalPropertyYearDetail> rentalDetails = new ArrayList<>();

        // Collect non-SS income first (needed for SS provisional income calc)
        BigDecimal nonSSIncome = BigDecimal.ZERO;
        BigDecimal ssBenefit = BigDecimal.ZERO;

        for (var source : sources) {
            int sourceAge = IncomeYearMath.resolveSourceAge(source, age, household, taxYear);
            if (!ProjectionIncomeSourceInput.isActiveForAge(source, sourceAge)) {
                continue;
            }

            BigDecimal multiplier = transitionMultiplier(source, sourceAge);
            BigDecimal amount = computeRealAmount(source, yearsFromBase, scenarioInflationRate)
                    .multiply(multiplier).setScale(SCALE, ROUNDING);
            if (source.incomeType() == IncomeSourceType.SOCIAL_SECURITY) {
                ssBenefit = ssBenefit.add(amount);
            } else {
                nonSSIncome = nonSSIncome.add(amount);
            }
        }

        // Combined Social Security taxability (audit B2 / T3-1): ALL Social Security sources share
        // ONE provisional-income computation. Provisional = non-SS income + static other income +
        // portfolio ordinary income realized this year (additionalProvisionalIncome) + 50% of the
        // AGGREGATED benefit -- so MFJ spousal benefits combine and portfolio withdrawals/RMDs/
        // conversions/gains drag SS into taxation.
        BigDecimal combinedSsTaxable = ssBenefit.compareTo(BigDecimal.ZERO) > 0
                ? ssTaxCalculator.computeTaxableAmount(
                        ssBenefit,
                        nonSSIncome.add(magi).add(additionalProvisionalIncome),
                        filingStatus.value(),
                        Math.max(0, taxYear - baseYear),
                        scenarioInflationRate)
                : BigDecimal.ZERO;

        for (var source : sources) {
            int sourceAge = IncomeYearMath.resolveSourceAge(source, age, household, taxYear);
            if (!ProjectionIncomeSourceInput.isActiveForAge(source, sourceAge)) {
                continue;
            }

            BigDecimal multiplier = transitionMultiplier(source, sourceAge);
            BigDecimal amount = computeRealAmount(source, yearsFromBase, scenarioInflationRate)
                    .multiply(multiplier).setScale(SCALE, ROUNDING);

            String sourceKey = source.id().toString();
            var result = switch (source.incomeType()) {
                case RENTAL_PROPERTY -> processRentalIncome(source, amount, taxYear, magi, suspendedLoss, multiplier);
                case SOCIAL_SECURITY -> processSocialSecurityIncome(amount);
                case PART_TIME_WORK  -> processEmploymentIncome(source, amount, taxYear);
                default              -> processDefaultIncome(source, amount);
            };

            totalCashInflow = totalCashInflow.add(result.cashInflow());
            totalTaxableIncome = totalTaxableIncome.add(result.taxableIncome());
            incomeBySource.merge(sourceKey, result.cashInflow(), BigDecimal::add);

            if (result instanceof RentalResult r) {
                rentalIncomeGross = rentalIncomeGross.add(amount);
                rentalExpensesTotal = rentalExpensesTotal.add(r.expenses());
                depreciationTotal = depreciationTotal.add(r.depreciation());
                rentalLossApplied = rentalLossApplied.add(r.lossApplied());
                netRentalTaxableIncome = netRentalTaxableIncome.add(r.taxableIncome());
                suspendedLoss = r.newSuspendedLoss();
                rentalDetails.add(new RentalPropertyYearDetail(
                        r.incomeSourceId(), r.propertyName(), r.taxTreatment(),
                        r.grossRent(), r.operatingExpenses(),
                        r.mortgageInterest(), r.propertyTax(),
                        r.depreciation(), r.taxableIncome(),
                        r.lossApplied(), r.newSuspendedLoss(),
                        r.newSuspendedLoss(),
                        r.cashInflow()));
            } else if (result instanceof EmploymentResult r) {
                seTax = seTax.add(r.seTax());
            }
        }

        // Social Security taxable income is the single combined figure, added once (its per-source
        // cash inflow was already folded into totalCashInflow / incomeBySource in the loop above).
        totalTaxableIncome = totalTaxableIncome.add(combinedSsTaxable);

        return new IncomeSourceYearResult(
                totalCashInflow, totalTaxableIncome,
                rentalIncomeGross, rentalExpensesTotal, depreciationTotal,
                rentalLossApplied, suspendedLoss, combinedSsTaxable, seTax,
                Map.copyOf(incomeBySource), List.copyOf(rentalDetails), netRentalTaxableIncome);
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
            BigDecimal cashInflow, BigDecimal taxableIncome)
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

    /**
     * A Social Security source contributes its benefit as CASH inflow only; the taxable portion is
     * computed once for all Social Security sources combined (see {@code process}), so per-source
     * taxable income is zero here to avoid double-counting.
     */
    private SocialSecurityResult processSocialSecurityIncome(BigDecimal benefit) {
        return new SocialSecurityResult(benefit, BigDecimal.ZERO);
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
        return IncomeYearMath.isBoundaryAge(source, age) ? new BigDecimal("0.5") : BigDecimal.ONE;
    }

    BigDecimal computeRealAmount(ProjectionIncomeSourceInput source, int yearsFromBase,
                                 BigDecimal scenarioInflationRate) {
        return IncomeYearMath.realAmount(source, yearsFromBase, scenarioInflationRate);
    }
}
