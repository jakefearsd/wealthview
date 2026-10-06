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
 * Parses a form string to a finite number, or undefined when it is blank or not a number. JSON
 * serialises NaN as null, which the server would read as "clear this value", so a NaN must never
 * reach the request.
 */
function finiteOrUndefined(value: string): number | undefined {
    if (value.trim() === '') return undefined;
    const n = parseFloat(value);
    return Number.isFinite(n) ? n : undefined;
}

function finiteIntOrUndefined(value: string): number | undefined {
    const n = finiteOrUndefined(value);
    return n === undefined ? undefined : Math.trunc(n);
}

function percentToFraction(value: string): number | undefined {
    const n = finiteOrUndefined(value);
    return n === undefined ? undefined : n / PERCENT;
}

const MAX_BONUS_RATE_PERCENT = 100;

/** Bonus depreciation is a percentage of the eligible basis, so it is held to 0-100. */
export function clampBonusRate(percent: number): number {
    return Math.min(MAX_BONUS_RATE_PERCENT, Math.max(0, percent));
}

function bonusRateFraction(value: string): number | undefined {
    const n = finiteOrUndefined(value);
    return n === undefined ? undefined : clampBonusRate(n) / PERCENT;
}

/**
 * Returns the first problem that would make the server reject the form (or silently drop data), or
 * undefined when the form is submittable. Suitable as the `validate` option of `useCrudForm`.
 */
export function validatePropertyForm(data: PropertyFormValues): string | undefined {
    if (data.address.trim() === '') return 'Address is required';
    const purchasePrice = finiteOrUndefined(data.purchasePrice);
    if (purchasePrice === undefined || purchasePrice < 0) return 'Enter a purchase price of 0 or more';
    if (data.purchaseDate === '') return 'Purchase date is required';
    const currentValue = finiteOrUndefined(data.currentValue);
    if (currentValue === undefined || currentValue < 0) return 'Enter a current value of 0 or more';

    if (data.showLoanDetails && finiteOrUndefined(data.loanAmount) !== undefined) {
        const rate = finiteOrUndefined(data.annualInterestRate);
        if (rate === undefined || rate < 0) return 'Enter an interest rate for the loan';
        const term = finiteOrUndefined(data.loanTermMonths);
        if (term === undefined || term <= 0) return 'Loan term must be greater than 0 months';
        if (data.loanStartDate === '') return 'Enter a start date for the loan';
    }

    if (data.depreciationMethod !== 'none') {
        const life = finiteOrUndefined(data.usefulLifeYears);
        if (life === undefined || life <= 0) return 'Useful life must be greater than 0 years';
    }
    return undefined;
}

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
        mortgage_balance: finiteOrUndefined(data.mortgageBalance),
        property_type: data.propertyType,
        ...(data.showLoanDetails && data.loanAmount ? {
            loan_amount: finiteOrUndefined(data.loanAmount),
            annual_interest_rate: percentToFraction(data.annualInterestRate),
            loan_term_months: finiteIntOrUndefined(data.loanTermMonths),
            loan_start_date: data.loanStartDate || undefined,
            use_computed_balance: data.useComputedBalance,
        } : {}),
        annual_appreciation_rate: percentToFraction(data.annualAppreciationRate),
        annual_property_tax: finiteOrUndefined(data.annualPropertyTax),
        annual_insurance_cost: finiteOrUndefined(data.annualInsuranceCost),
        annual_maintenance_cost: finiteOrUndefined(data.annualMaintenanceCost),
        depreciation_method: data.depreciationMethod,
        in_service_date: data.depreciationMethod !== 'none' ? (data.inServiceDate || data.purchaseDate || undefined) : undefined,
        land_value: finiteOrUndefined(data.landValue),
        useful_life_years: finiteOrUndefined(data.usefulLifeYears),
        ...(isCostSeg ? {
            cost_seg_allocations: buildCostSegAllocations(data.costSegAllocations),
            bonus_depreciation_rate: bonusRateFraction(data.bonusDepreciationRate),
            cost_seg_study_year: finiteIntOrUndefined(data.costSegStudyYear),
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
