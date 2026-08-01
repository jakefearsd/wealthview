import { describe, it, expect } from 'vitest';
import { buildRequest, buildCostSegAllocations, allocationsToState } from './propertyRequest';
import type { PropertyFormValues } from '../components/PropertyForm';

const baseForm: PropertyFormValues = {
    address: '123 Main St',
    purchasePrice: '400000',
    purchaseDate: '2020-06-01',
    currentValue: '520000',
    mortgageBalance: '250000',
    showLoanDetails: false,
    loanAmount: '',
    annualInterestRate: '',
    loanTermMonths: '',
    loanStartDate: '',
    useComputedBalance: false,
    propertyType: 'rental',
    showFinancialAssumptions: false,
    annualAppreciationRate: '',
    annualPropertyTax: '',
    annualInsuranceCost: '',
    annualMaintenanceCost: '',
    showDepreciation: false,
    depreciationMethod: 'none',
    inServiceDate: '',
    landValue: '',
    usefulLifeYears: '27.5',
    costSegAllocations: { fiveYr: '', sevenYr: '', fifteenYr: '', twentySevenYr: '' },
    bonusDepreciationRate: '100',
    costSegStudyYear: '',
};

const form = (overrides: Partial<PropertyFormValues> = {}): PropertyFormValues => ({
    ...baseForm,
    ...overrides,
});

describe('buildRequest', () => {
    it('parses the always-present numeric fields', () => {
        const req = buildRequest(form());

        expect(req.address).toBe('123 Main St');
        expect(req.purchase_price).toBe(400000);
        expect(req.current_value).toBe(520000);
        expect(req.mortgage_balance).toBe(250000);
        expect(req.property_type).toBe('rental');
    });

    it('omits a blank mortgage balance rather than sending zero', () => {
        // parseFloat('') is NaN, which would serialise as null and overwrite a real balance.
        expect(buildRequest(form({ mortgageBalance: '' })).mortgage_balance).toBeUndefined();
    });

    // === percentage conversions ===
    //
    // Rates are typed as whole percents and stored as fractions. Getting these wrong is not a
    // rendering bug: a 5% appreciation assumption stored as 5.0 compounds to a 30-year projection
    // that is wrong by orders of magnitude.

    it('converts the appreciation rate from percent to a fraction', () => {
        expect(buildRequest(form({ annualAppreciationRate: '3.5' })).annual_appreciation_rate)
            .toBeCloseTo(0.035, 10);
    });

    it('omits the appreciation rate entirely when left blank', () => {
        expect(buildRequest(form({ annualAppreciationRate: '' })).annual_appreciation_rate)
            .toBeUndefined();
    });

    it('converts the loan interest rate from percent to a fraction', () => {
        const req = buildRequest(form({
            showLoanDetails: true,
            loanAmount: '320000',
            annualInterestRate: '6.25',
            loanTermMonths: '360',
            loanStartDate: '2020-06-01',
            useComputedBalance: true,
        }));

        expect(req.annual_interest_rate).toBeCloseTo(0.0625, 10);
        expect(req.loan_amount).toBe(320000);
        expect(req.loan_term_months).toBe(360);
        expect(req.use_computed_balance).toBe(true);
    });

    // === conditional blocks ===

    it('omits every loan key when loan details are hidden', () => {
        const req = buildRequest(form({ showLoanDetails: false, loanAmount: '320000' }));

        expect(req).not.toHaveProperty('loan_amount');
        expect(req).not.toHaveProperty('annual_interest_rate');
    });

    it('omits every loan key when loan details are shown but no amount was entered', () => {
        const req = buildRequest(form({ showLoanDetails: true, loanAmount: '' }));

        expect(req).not.toHaveProperty('loan_amount');
    });

    it('omits every cost-segregation key unless that method is selected', () => {
        const req = buildRequest(form({
            depreciationMethod: 'straight_line',
            costSegAllocations: { fiveYr: '50000', sevenYr: '', fifteenYr: '', twentySevenYr: '' },
        }));

        expect(req).not.toHaveProperty('cost_seg_allocations');
        expect(req).not.toHaveProperty('bonus_depreciation_rate');
    });

    it('includes cost-segregation keys and converts the bonus rate when selected', () => {
        const req = buildRequest(form({
            depreciationMethod: 'cost_segregation',
            bonusDepreciationRate: '60',
            costSegStudyYear: '2024',
            costSegAllocations: { fiveYr: '50000', sevenYr: '', fifteenYr: '20000', twentySevenYr: '' },
        }));

        expect(req.bonus_depreciation_rate).toBeCloseTo(0.6, 10);
        expect(req.cost_seg_study_year).toBe(2024);
        expect(req.cost_seg_allocations).toEqual([
            { asset_class: '5yr', allocation: 50000 },
            { asset_class: '15yr', allocation: 20000 },
        ]);
    });

    // === in-service date fallback chain ===

    it('leaves the in-service date undefined when depreciation is off', () => {
        const req = buildRequest(form({ depreciationMethod: 'none', inServiceDate: '2021-01-01' }));

        expect(req.in_service_date).toBeUndefined();
    });

    it('uses the explicit in-service date when depreciation is on', () => {
        const req = buildRequest(form({
            depreciationMethod: 'straight_line',
            inServiceDate: '2021-01-01',
        }));

        expect(req.in_service_date).toBe('2021-01-01');
    });

    it('falls back to the purchase date when depreciation is on but no in-service date is given', () => {
        const req = buildRequest(form({ depreciationMethod: 'straight_line', inServiceDate: '' }));

        expect(req.in_service_date).toBe('2020-06-01');
    });

    it('leaves the in-service date undefined when neither date is available', () => {
        const req = buildRequest(form({
            depreciationMethod: 'straight_line',
            inServiceDate: '',
            purchaseDate: '',
        }));

        expect(req.in_service_date).toBeUndefined();
    });
});

describe('buildCostSegAllocations', () => {
    it('includes only the buckets with a positive amount', () => {
        const result = buildCostSegAllocations({
            fiveYr: '50000', sevenYr: '', fifteenYr: '0', twentySevenYr: '300000',
        });

        expect(result).toEqual([
            { asset_class: '5yr', allocation: 50000 },
            { asset_class: '27_5yr', allocation: 300000 },
        ]);
    });

    it('treats a zero allocation as not allocated rather than allocated zero', () => {
        expect(buildCostSegAllocations({
            fiveYr: '0', sevenYr: '0', fifteenYr: '0', twentySevenYr: '0',
        })).toEqual([]);
    });

    it('drops negative allocations', () => {
        expect(buildCostSegAllocations({
            fiveYr: '-1000', sevenYr: '', fifteenYr: '', twentySevenYr: '',
        })).toEqual([]);
    });

    it('emits the four buckets in ascending asset-class order', () => {
        const result = buildCostSegAllocations({
            fiveYr: '1', sevenYr: '2', fifteenYr: '3', twentySevenYr: '4',
        });

        expect(result.map((a) => a.asset_class)).toEqual(['5yr', '7yr', '15yr', '27_5yr']);
    });
});

describe('allocationsToState', () => {
    it('maps API allocations back into form fields', () => {
        const state = allocationsToState([
            { asset_class: '5yr', allocation: 50000 },
            { asset_class: '27_5yr', allocation: 300000 },
        ]);

        expect(state).toEqual({
            fiveYr: '50000', sevenYr: '', fifteenYr: '', twentySevenYr: '300000',
        });
    });

    it('returns all-empty fields for null or empty allocations', () => {
        const empty = { fiveYr: '', sevenYr: '', fifteenYr: '', twentySevenYr: '' };

        expect(allocationsToState(null)).toEqual(empty);
        expect(allocationsToState(undefined)).toEqual(empty);
        expect(allocationsToState([])).toEqual(empty);
    });

    it('ignores an unrecognised asset class rather than throwing', () => {
        const state = allocationsToState([
            { asset_class: '39yr', allocation: 1000 },
            { asset_class: '7yr', allocation: 25000 },
        ] as Parameters<typeof allocationsToState>[0]);

        expect(state.sevenYr).toBe('25000');
        expect(state.fiveYr).toBe('');
    });

    it('round-trips through buildCostSegAllocations without loss', () => {
        const original = { fiveYr: '50000', sevenYr: '25000', fifteenYr: '', twentySevenYr: '300000' };

        expect(allocationsToState(buildCostSegAllocations(original))).toEqual(original);
    });
});
