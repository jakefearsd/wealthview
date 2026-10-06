import { describe, it, expect } from 'vitest';
import { accountTypeLabel, capitalize } from './accountTypes';

describe('accountTypeLabel', () => {
    it.each([
        ['401k', '401(k)'],
        ['ira', 'IRA'],
        ['roth', 'Roth IRA'],
        ['brokerage', 'Brokerage'],
        ['bank', 'Bank'],
    ])('maps %s to %s', (type, label) => {
        expect(accountTypeLabel(type)).toBe(label);
    });

    it('capitalises an unknown type rather than leaking the raw value', () => {
        expect(accountTypeLabel('property')).toBe('Property');
    });

    it('returns an empty string for an empty type', () => {
        expect(accountTypeLabel('')).toBe('');
    });
});

describe('capitalize', () => {
    it('upper-cases only the first letter', () => {
        expect(capitalize('dividend')).toBe('Dividend');
    });
});
