import { describe, it, expect } from 'vitest';
import { interpolateMonthly } from './monthlyInterpolation';
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
        rental_income_gross: null,
        rental_expenses_total: null,
        depreciation_total: null,
        rental_loss_applied: null,
        suspended_loss_carryforward: null,
        social_security_taxable: null,
        self_employment_tax: null,
        ...overrides,
    };
}

describe('interpolateMonthly', () => {
    it('emptyInput_returnsEmpty', () => {
        expect(interpolateMonthly([])).toEqual([]);
    });

    it('singleYear_returns12Points', () => {
        const data = [makeYear({
            year: 2026, start_balance: 100000, end_balance: 107000,
            growth: 7000, contributions: 0, withdrawals: 0,
        })];
        const result = interpolateMonthly(data);
        expect(result).toHaveLength(12);
        expect(result[0].label).toBe('2026-01');
        expect(result[11].label).toBe('2026-12');
        expect(result[0].month).toBe(1);
        expect(result[11].month).toBe(12);
        expect(result[0].year).toBe(2026);
    });

    it('firstPointEqualsStartBalance', () => {
        const data = [makeYear({
            year: 2026, start_balance: 500000, end_balance: 535000,
            growth: 35000, contributions: 0, withdrawals: 0,
        })];
        const result = interpolateMonthly(data);
        expect(result[0].balance).toBe(500000);
    });

    it('lastPointMatchesEndBalance', () => {
        const data = [makeYear({
            year: 2026, start_balance: 500000, end_balance: 535000,
            growth: 35000, contributions: 0, withdrawals: 0,
        })];
        const result = interpolateMonthly(data);
        expect(result[11].balance).toBeCloseTo(535000, 2);
    });

    it('poolsSnapAtYearEnd', () => {
        const data = [makeYear({
            year: 2026, start_balance: 300000, end_balance: 321000,
            growth: 21000, contributions: 0, withdrawals: 0,
            traditional_balance: 150000, roth_balance: 121000, taxable_balance: 50000,
        })];
        const result = interpolateMonthly(data);
        expect(result[11].traditional_balance).toBe(150000);
        expect(result[11].roth_balance).toBe(121000);
        expect(result[11].taxable_balance).toBe(50000);
    });

    it('rothConversionInDecemberOnly', () => {
        const data = [makeYear({
            year: 2026, start_balance: 300000, end_balance: 321000,
            growth: 21000, contributions: 0, withdrawals: 0,
            traditional_balance: 130000, roth_balance: 141000, taxable_balance: 50000,
            roth_conversion_amount: 20000,
        })];
        const result = interpolateMonthly(data);
        for (let i = 0; i < 11; i++) {
            expect(result[i].roth_conversion_amount).toBeNull();
        }
        expect(result[11].roth_conversion_amount).toBe(20000);
    });

    it('growthDistributedSmoothly', () => {
        const data = [makeYear({
            year: 2026, start_balance: 100000, end_balance: 107000,
            growth: 7000, contributions: 0, withdrawals: 0,
        })];
        const result = interpolateMonthly(data);
        for (let i = 1; i < 12; i++) {
            expect(result[i].balance).toBeGreaterThan(result[i - 1].balance);
        }
    });

    it('contributionsSpreadMonthly', () => {
        const data = [makeYear({
            year: 2026, start_balance: 100000, end_balance: 112000,
            growth: 0, contributions: 12000, withdrawals: 0,
        })];
        const result = interpolateMonthly(data);
        // Months 2-11 should each add ~1000 in contributions (12000/12)
        // Month 12 is snapped to end_balance so excluded
        for (let i = 1; i < 11; i++) {
            const delta = result[i].balance - result[i - 1].balance;
            expect(delta).toBeCloseTo(1000, 0);
        }
    });

    it('withdrawalsSpreadMonthly', () => {
        const data = [makeYear({
            year: 2026, start_balance: 200000, end_balance: 176000,
            growth: 0, contributions: 0, withdrawals: 24000, retired: true,
        })];
        const result = interpolateMonthly(data);
        // Months 2-11 should each subtract ~2000 in withdrawals (24000/12)
        // Month 12 is snapped to end_balance so excluded
        for (let i = 1; i < 11; i++) {
            const delta = result[i].balance - result[i - 1].balance;
            expect(delta).toBeCloseTo(-2000, 0);
        }
    });

    it('zeroStartBalance_handlesGracefully', () => {
        const data = [makeYear({
            year: 2026, start_balance: 0, end_balance: 12000,
            growth: 0, contributions: 12000, withdrawals: 0,
        })];
        const result = interpolateMonthly(data);
        expect(result).toHaveLength(12);
        expect(result[0].balance).toBe(0);
        expect(result[11].balance).toBeCloseTo(12000, 2);
    });

    it('multipleYears_yearBoundariesAlign', () => {
        const data = [
            makeYear({
                year: 2026, start_balance: 100000, end_balance: 107000,
                growth: 7000, contributions: 0, withdrawals: 0,
                traditional_balance: 50000, roth_balance: 37000, taxable_balance: 20000,
            }),
            makeYear({
                year: 2027, start_balance: 107000, end_balance: 114490,
                growth: 7490, contributions: 0, withdrawals: 0,
                traditional_balance: 53500, roth_balance: 39590, taxable_balance: 21400,
            }),
        ];
        const result = interpolateMonthly(data);
        expect(result).toHaveLength(24);
        // Year 1 month 12 balance should match year 2 month 1 start
        // Month 12 of year 1 is snapped to end_balance = 107000
        // Month 1 of year 2 starts at start_balance = 107000
        expect(result[11].balance).toBeCloseTo(107000, 2);
        expect(result[12].balance).toBe(107000);
        expect(result[12].label).toBe('2027-01');
    });

    it('poolsTrackProportionallyBeforeSnap', () => {
        const data = [makeYear({
            year: 2026, start_balance: 300000, end_balance: 321000,
            growth: 21000, contributions: 0, withdrawals: 0,
            traditional_balance: 150000, roth_balance: 121000, taxable_balance: 50000,
        })];
        const result = interpolateMonthly(data);
        // Mid-year pool values should be non-null and sum to balance
        const mid = result[5];
        expect(mid.traditional_balance).not.toBeNull();
        expect(mid.roth_balance).not.toBeNull();
        expect(mid.taxable_balance).not.toBeNull();
        const poolSum = mid.traditional_balance! + mid.roth_balance! + mid.taxable_balance!;
        expect(poolSum).toBeCloseTo(mid.balance, 2);
    });
});
