import { describe, it, expect } from 'vitest';
import {
    findPeakBalance,
    findDepletionYear,
    computeCumulativeContributions,
    findCrossoverYear,
    computeTaxShieldSummary,
    computeTaxMetrics,
    computeTotalSpending,
    computePlanOutcome,
} from './projectionCalcs';
import type { ProjectionYear, RentalPropertyYearDetail, SpendingFeasibility } from '../types/projection';

function makeYear(overrides: Partial<ProjectionYear> & { year: number }): ProjectionYear {
    return {
        age: 30 + overrides.year - 2024,
        start_balance: 0,
        contributions: 0,
        growth: 0,
        withdrawals: 0,
        end_balance: 0,
        retired: false,
        traditional_balance: null,
        roth_balance: null,
        taxable_balance: null,
        roth_conversion_amount: null,
        tax_liability: null,
        essential_expenses: null,
        discretionary_expenses: null,
        income_streams_total: null,
        net_spending_need: null,
        spending_surplus: null,
        discretionary_after_cuts: null,
        rental_income_gross: null,
        rental_expenses_total: null,
        depreciation_total: null,
        rental_loss_applied: null,
        suspended_loss_carryforward: null,
        social_security_taxable: null,
        self_employment_tax: null,
        income_by_source: null,
        property_equity: null,
        total_net_worth: null,
        surplus_reinvested: null,
        taxable_growth: null,
        traditional_growth: null,
        roth_growth: null,
        tax_paid_from_taxable: null,
        tax_paid_from_traditional: null,
        tax_paid_from_roth: null,
        withdrawal_from_taxable: null,
        withdrawal_from_traditional: null,
        withdrawal_from_roth: null,
        rental_property_details: null,
        federal_tax: null,
        state_tax: null,
        salt_deduction: null,
        used_itemized_deduction: null,
        rmd_amount: null,
        capital_gains_tax: null,
        ...overrides,
    };
}

describe('findPeakBalance', () => {
    it('returns last year when balances are increasing', () => {
        const data = [
            makeYear({ year: 2024, end_balance: 100000 }),
            makeYear({ year: 2025, end_balance: 200000 }),
            makeYear({ year: 2026, end_balance: 300000 }),
        ];
        expect(findPeakBalance(data)).toEqual({ year: 2026, balance: 300000 });
    });

    it('returns correct year when peak is in the middle', () => {
        const data = [
            makeYear({ year: 2024, end_balance: 100000 }),
            makeYear({ year: 2025, end_balance: 500000 }),
            makeYear({ year: 2026, end_balance: 200000 }),
        ];
        expect(findPeakBalance(data)).toEqual({ year: 2025, balance: 500000 });
    });

    it('returns zero values for empty data', () => {
        expect(findPeakBalance([])).toEqual({ year: 0, balance: 0 });
    });
});

describe('findDepletionYear', () => {
    it('returns year and age when balance hits zero', () => {
        const data = [
            makeYear({ year: 2024, age: 60, end_balance: 50000 }),
            makeYear({ year: 2025, age: 61, end_balance: 0 }),
            makeYear({ year: 2026, age: 62, end_balance: 0 }),
        ];
        expect(findDepletionYear(data)).toEqual({ year: 2025, age: 61 });
    });

    it('returns null when balance stays positive', () => {
        const data = [
            makeYear({ year: 2024, end_balance: 100000 }),
            makeYear({ year: 2025, end_balance: 200000 }),
        ];
        expect(findDepletionYear(data)).toBeNull();
    });

    it('returns null for empty data', () => {
        expect(findDepletionYear([])).toBeNull();
    });
});

describe('computeCumulativeContributions', () => {
    it('returns running sum of contributions', () => {
        const data = [
            makeYear({ year: 2024, contributions: 10000 }),
            makeYear({ year: 2025, contributions: 10000 }),
            makeYear({ year: 2026, contributions: 5000 }),
        ];
        expect(computeCumulativeContributions(data)).toEqual([10000, 20000, 25000]);
    });

    it('returns empty array for empty data', () => {
        expect(computeCumulativeContributions([])).toEqual([]);
    });
});

function makeRentalDetail(overrides: Partial<RentalPropertyYearDetail> = {}): RentalPropertyYearDetail {
    return {
        income_source_id: 'src-1',
        property_name: 'Main St Duplex',
        tax_treatment: 'rental_passive',
        gross_rent: 0,
        operating_expenses: 0,
        mortgage_interest: 0,
        property_tax: 0,
        depreciation: 0,
        net_taxable_income: 0,
        loss_applied_to_income: 0,
        loss_suspended: 0,
        suspended_loss_carryforward: 0,
        cash_flow: 0,
        ...overrides,
    };
}

function makeFeasibility(overrides: Partial<SpendingFeasibility> = {}): SpendingFeasibility {
    return {
        spending_feasible: true,
        first_shortfall_year: null,
        first_shortfall_age: null,
        sustainable_annual_spending: 80000,
        required_annual_spending: 60000,
        ...overrides,
    };
}

describe('computeTaxShieldSummary', () => {
    it('aggregates depreciation and applied losses across retired years only', () => {
        const data = [
            makeYear({ year: 2024, retired: false, depreciation_total: 9999, rental_loss_applied: 9999 }),
            makeYear({ year: 2025, retired: true, depreciation_total: 10000, rental_loss_applied: 3000 }),
            makeYear({ year: 2026, retired: true, depreciation_total: 12000, rental_loss_applied: 2000 }),
        ];

        const summary = computeTaxShieldSummary(data);

        expect(summary.totalDepreciation).toBe(22000);
        expect(summary.totalLossApplied).toBe(5000);
    });

    it('estimates tax savings from the effective rate on taxable income', () => {
        const data = [
            makeYear({
                year: 2025, retired: true, rental_loss_applied: 1000,
                tax_liability: 5000, income_streams_total: 40000, roth_conversion_amount: 10000,
            }),
        ];

        const summary = computeTaxShieldSummary(data);

        // effective rate = 5000 / 50000 = 10%; savings = 1000 * 10% = 100
        expect(summary.estimatedTaxSavings).toBeCloseTo(100);
    });

    it('shelters the smaller of loss and Roth conversion amount', () => {
        const data = [
            makeYear({ year: 2025, retired: true, rental_loss_applied: 8000, roth_conversion_amount: 5000 }),
            makeYear({ year: 2026, retired: true, rental_loss_applied: 2000, roth_conversion_amount: 5000 }),
        ];

        const summary = computeTaxShieldSummary(data);

        expect(summary.rothConversionSheltered).toBe(7000);
    });

    it('reports the suspended loss carryforward from the final retired year', () => {
        const data = [
            makeYear({ year: 2025, retired: true, suspended_loss_carryforward: 4000 }),
            makeYear({ year: 2026, retired: true, suspended_loss_carryforward: 1500 }),
        ];

        const summary = computeTaxShieldSummary(data);

        expect(summary.suspendedLossRemaining).toBe(1500);
    });

    it('aggregates per-property depreciation and applied losses by income source', () => {
        const data = [
            makeYear({
                year: 2025, retired: true,
                rental_property_details: [
                    makeRentalDetail({ income_source_id: 'a', property_name: 'A', depreciation: 100, loss_applied_to_income: 10 }),
                    makeRentalDetail({ income_source_id: 'b', property_name: 'B', tax_treatment: 'rental_active_reps', depreciation: 200, loss_applied_to_income: 20 }),
                ],
            }),
            makeYear({
                year: 2026, retired: true,
                rental_property_details: [
                    makeRentalDetail({ income_source_id: 'a', property_name: 'A', depreciation: 300, loss_applied_to_income: 30 }),
                ],
            }),
        ];

        const summary = computeTaxShieldSummary(data);

        expect(summary.perProperty).toEqual([
            { name: 'A', taxTreatment: 'rental_passive', depreciation: 400, lossApplied: 40 },
            { name: 'B', taxTreatment: 'rental_active_reps', depreciation: 200, lossApplied: 20 },
        ]);
    });

    it('returns zeroed totals for empty data', () => {
        const summary = computeTaxShieldSummary([]);

        expect(summary.totalDepreciation).toBe(0);
        expect(summary.totalLossApplied).toBe(0);
        expect(summary.suspendedLossRemaining).toBe(0);
        expect(summary.perProperty).toEqual([]);
    });
});

describe('computeTaxMetrics', () => {
    it('returns null when no retired year has a tax liability', () => {
        const data = [
            makeYear({ year: 2024, retired: false, tax_liability: 5000 }),
            makeYear({ year: 2025, retired: true, tax_liability: null }),
        ];

        expect(computeTaxMetrics(data)).toBeNull();
    });

    it('sums lifetime tax and averages the effective rate across retired years', () => {
        const data = [
            makeYear({ year: 2025, retired: true, tax_liability: 5000, income_streams_total: 50000 }),
            makeYear({ year: 2026, retired: true, tax_liability: 10000, withdrawal_from_traditional: 50000 }),
        ];

        const metrics = computeTaxMetrics(data);

        expect(metrics).not.toBeNull();
        expect(metrics!.lifetimeTax).toBe(15000);
        // rates: 10% and 20% -> avg 15.0
        expect(metrics!.avgRate).toBe(15);
        expect(metrics!.totalRetiredYears).toBe(2);
        expect(metrics!.hasStateTax).toBe(false);
    });

    it('tracks state tax, SALT, and itemized-year counts', () => {
        const data = [
            makeYear({
                year: 2025, retired: true, tax_liability: 5000,
                state_tax: 1200, salt_deduction: 10000, used_itemized_deduction: true,
            }),
            makeYear({
                year: 2026, retired: true, tax_liability: 4000,
                state_tax: 1100, salt_deduction: 9000, used_itemized_deduction: false,
            }),
        ];

        const metrics = computeTaxMetrics(data);

        expect(metrics!.hasStateTax).toBe(true);
        expect(metrics!.totalStateTax).toBe(2300);
        expect(metrics!.totalSalt).toBe(10000);
        expect(metrics!.itemizedCount).toBe(1);
    });
});

describe('computeTotalSpending', () => {
    it('uses withdrawals plus income streams for retired years with activity', () => {
        const y = makeYear({ year: 2025, retired: true, withdrawals: 40000, income_streams_total: 20000 });

        expect(computeTotalSpending(y)).toBe(60000);
    });

    it('falls back to profile expenses when the retired year has no withdrawals or income', () => {
        const y = makeYear({
            year: 2025, retired: true, withdrawals: 0, income_streams_total: 0,
            essential_expenses: 30000, discretionary_expenses: 10000,
        });

        expect(computeTotalSpending(y)).toBe(40000);
    });

    it('prefers discretionary_after_cuts over discretionary_expenses', () => {
        const y = makeYear({
            year: 2025, retired: false,
            essential_expenses: 30000, discretionary_expenses: 10000, discretionary_after_cuts: 6000,
        });

        expect(computeTotalSpending(y)).toBe(36000);
    });

    it('returns null when no spending data is available', () => {
        const y = makeYear({ year: 2025, retired: false });

        expect(computeTotalSpending(y)).toBeNull();
    });
});

describe('computePlanOutcome', () => {
    it('reports depletion year when no spending profile is linked', () => {
        const outcome = computePlanOutcome(null, { year: 2040, age: 75 });

        expect(outcome.label).toBe('Depletion Year');
        expect(outcome.value).toBe('2040 (age 75)');
        expect(outcome.color).toBe('#d32f2f');
    });

    it('reports "Never" in green when no profile is linked and money outlasts the plan', () => {
        const outcome = computePlanOutcome(null, null);

        expect(outcome.label).toBe('Depletion Year');
        expect(outcome.value).toBe('Never');
        expect(outcome.color).toBe('#2e7d32');
    });

    it('reports depleted-at-age when a profile is linked and the portfolio runs out', () => {
        const outcome = computePlanOutcome(makeFeasibility(), { year: 2040, age: 75 });

        expect(outcome.label).toBe('Plan Outcome');
        expect(outcome.value).toBe('Depleted at age 75');
        expect(outcome.color).toBe('#d32f2f');
        expect(outcome.description).toContain('2040');
    });

    it('reports fully sustainable when the plan is feasible', () => {
        const outcome = computePlanOutcome(makeFeasibility({ spending_feasible: true }), null);

        expect(outcome.label).toBe('Plan Outcome');
        expect(outcome.value).toBe('Fully Sustainable');
        expect(outcome.color).toBe('#2e7d32');
    });

    it('reports underfunded with the first shortfall age when infeasible', () => {
        const outcome = computePlanOutcome(
            makeFeasibility({ spending_feasible: false, first_shortfall_age: 82, sustainable_annual_spending: 50000, required_annual_spending: 70000 }),
            null,
        );

        expect(outcome.label).toBe('Plan Outcome');
        expect(outcome.value).toBe('Underfunded at age 82');
        expect(outcome.color).toBe('#d32f2f');
        expect(outcome.description).toContain('$50,000');
    });
});

describe('findCrossoverYear', () => {
    it('returns null when no spending profile is linked', () => {
        const data = [
            makeYear({ year: 2024, age: 60, retired: true, growth: 50000, withdrawals: 40000, end_balance: 500000 }),
            makeYear({ year: 2025, age: 61, retired: true, growth: 45000, withdrawals: 40000, end_balance: 450000 }),
        ];
        expect(findCrossoverYear(data)).toBeNull();
    });

    it('returns null when withdrawals never exceed growth', () => {
        const data = [
            makeYear({ year: 2024, age: 60, retired: true, essential_expenses: 30000, growth: 50000, withdrawals: 40000, end_balance: 500000 }),
            makeYear({ year: 2025, age: 61, retired: true, essential_expenses: 31000, growth: 45000, withdrawals: 40000, end_balance: 510000 }),
        ];
        expect(findCrossoverYear(data)).toBeNull();
    });

    it('returns the first retired year where withdrawals exceed growth', () => {
        const data = [
            makeYear({ year: 2024, age: 60, retired: true, essential_expenses: 30000, growth: 50000, withdrawals: 40000, end_balance: 500000 }),
            makeYear({ year: 2025, age: 61, retired: true, essential_expenses: 35000, growth: 45000, withdrawals: 40000, end_balance: 480000 }),
            makeYear({ year: 2026, age: 62, retired: true, essential_expenses: 40000, growth: 35000, withdrawals: 40000, end_balance: 440000 }),
        ];
        expect(findCrossoverYear(data)).toEqual({ year: 2026, age: 62 });
    });

    it('skips pre-retirement years where withdrawals exceed growth', () => {
        const data = [
            makeYear({ year: 2024, age: 58, retired: false, essential_expenses: 30000, growth: 10000, withdrawals: 20000, end_balance: 300000 }),
            makeYear({ year: 2025, age: 59, retired: true, essential_expenses: 30000, growth: 50000, withdrawals: 40000, end_balance: 350000 }),
        ];
        expect(findCrossoverYear(data)).toBeNull();
    });

    it('skips depleted years where balance is zero', () => {
        const data = [
            makeYear({ year: 2024, age: 60, retired: true, essential_expenses: 30000, growth: 50000, withdrawals: 40000, end_balance: 500000 }),
            makeYear({ year: 2025, age: 61, retired: true, essential_expenses: 30000, growth: 0, withdrawals: 40000, end_balance: 0 }),
        ];
        expect(findCrossoverYear(data)).toBeNull();
    });

    it('returns null for empty data', () => {
        expect(findCrossoverYear([])).toBeNull();
    });
});
