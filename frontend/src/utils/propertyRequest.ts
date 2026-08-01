import type { CostSegAllocations, PropertyFormValues } from '../components/PropertyForm';
import type { Property } from '../types/property';

/**
 * Translation between the property form's all-strings state and the API's typed request.
 *
 * <p>Extracted from PropertiesListPage and PropertyDetailPage, which each carried a byte-identical
 * private copy of all three functions — one on the create path, one on the edit path. Duplicated
 * they were a live drift hazard (a fix applied to "the" builder would only ever land on one of the
 * two), and, being module-private, they were reachable only by driving a full form submit through
 * a page render, which is why none of this logic was directly covered.
 *
 * The percentage conversions here are the reason that matters: rates are entered as whole percents
 * and stored as fractions, so a misplaced `/ 100` turns a 5% appreciation assumption into 500% and
 * compounds it across a 30-year projection.
 */

/** Percent-entered rates are persisted as fractions. */
const PERCENT = 100;

/**
 * Maps the four cost-segregation buckets to API allocations, dropping any that are blank or
 * non-positive — an unfilled bucket is "not allocated", not "allocated zero".
 */
export function buildCostSegAllocations(allocs: CostSegAllocations) {
    const result = [];
    if (allocs.fiveYr && parseFloat(allocs.fiveYr) > 0) result.push({ asset_class: '5yr', allocation: parseFloat(allocs.fiveYr) });
    if (allocs.sevenYr && parseFloat(allocs.sevenYr) > 0) result.push({ asset_class: '7yr', allocation: parseFloat(allocs.sevenYr) });
    if (allocs.fifteenYr && parseFloat(allocs.fifteenYr) > 0) result.push({ asset_class: '15yr', allocation: parseFloat(allocs.fifteenYr) });
    if (allocs.twentySevenYr && parseFloat(allocs.twentySevenYr) > 0) result.push({ asset_class: '27_5yr', allocation: parseFloat(allocs.twentySevenYr) });
    return result;
}

/**
 * Builds the create/update request from form state. The loan block and the cost-segregation block
 * are each spread in only when they apply, so an unused section contributes no keys at all rather
 * than a set of undefineds.
 */
export function buildRequest(data: PropertyFormValues) {
    const isCostSeg = data.depreciationMethod === 'cost_segregation';
    return {
        address: data.address,
        purchase_price: parseFloat(data.purchasePrice),
        purchase_date: data.purchaseDate,
        current_value: parseFloat(data.currentValue),
        mortgage_balance: data.mortgageBalance ? parseFloat(data.mortgageBalance) : undefined,
        property_type: data.propertyType,
        ...(data.showLoanDetails && data.loanAmount ? {
            loan_amount: parseFloat(data.loanAmount),
            annual_interest_rate: parseFloat(data.annualInterestRate) / PERCENT,
            loan_term_months: parseInt(data.loanTermMonths),
            loan_start_date: data.loanStartDate,
            use_computed_balance: data.useComputedBalance,
        } : {}),
        annual_appreciation_rate: data.annualAppreciationRate ? parseFloat(data.annualAppreciationRate) / PERCENT : undefined,
        annual_property_tax: data.annualPropertyTax ? parseFloat(data.annualPropertyTax) : undefined,
        annual_insurance_cost: data.annualInsuranceCost ? parseFloat(data.annualInsuranceCost) : undefined,
        annual_maintenance_cost: data.annualMaintenanceCost ? parseFloat(data.annualMaintenanceCost) : undefined,
        depreciation_method: data.depreciationMethod,
        in_service_date: data.depreciationMethod !== 'none' ? (data.inServiceDate || data.purchaseDate || undefined) : undefined,
        land_value: data.landValue ? parseFloat(data.landValue) : undefined,
        useful_life_years: data.usefulLifeYears ? parseFloat(data.usefulLifeYears) : undefined,
        ...(isCostSeg ? {
            cost_seg_allocations: buildCostSegAllocations(data.costSegAllocations),
            bonus_depreciation_rate: parseFloat(data.bonusDepreciationRate) / PERCENT,
            cost_seg_study_year: data.costSegStudyYear ? parseInt(data.costSegStudyYear) : undefined,
        } : {}),
    };
}

/** Inverse of {@link buildCostSegAllocations}: API allocations back into form state. */
export function allocationsToState(allocs: Property['cost_seg_allocations']): CostSegAllocations {
    const state: CostSegAllocations = { fiveYr: '', sevenYr: '', fifteenYr: '', twentySevenYr: '' };
    for (const a of allocs ?? []) {
        if (a.asset_class === '5yr') state.fiveYr = String(a.allocation);
        else if (a.asset_class === '7yr') state.sevenYr = String(a.allocation);
        else if (a.asset_class === '15yr') state.fifteenYr = String(a.allocation);
        else if (a.asset_class === '27_5yr') state.twentySevenYr = String(a.allocation);
    }
    return state;
}
