import { describe, it, expect } from 'vitest';
import { allocationSum, isAllocationValid } from './allocation';

describe('allocationSum', () => {
    it('sums all four asset classes', () => {
        expect(allocationSum({ us_stock: 60, intl_stock: 20, bond: 15, cash: 5 })).toBe(100);
    });
});

describe('isAllocationValid', () => {
    it('treats null as valid (derive from holdings)', () => {
        expect(isAllocationValid(null)).toBe(true);
    });

    it('treats a sum of 100 as valid', () => {
        expect(isAllocationValid({ us_stock: 60, intl_stock: 20, bond: 15, cash: 5 })).toBe(true);
    });

    it('treats a sum within 0.01 of 100 as valid', () => {
        expect(isAllocationValid({ us_stock: 60.005, intl_stock: 20, bond: 15, cash: 5 })).toBe(true);
    });

    it('treats a sum far from 100 as invalid', () => {
        expect(isAllocationValid({ us_stock: 60, intl_stock: 20, bond: 15, cash: 10 })).toBe(false);
    });
});
