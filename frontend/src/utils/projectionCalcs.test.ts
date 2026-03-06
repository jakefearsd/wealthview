import { describe, it, expect } from 'vitest';
import { findPeakBalance, findDepletionYear, computeCumulativeContributions } from './projectionCalcs';
import type { ProjectionYear } from '../types/projection';

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
