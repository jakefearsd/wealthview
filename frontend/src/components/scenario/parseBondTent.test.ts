import { describe, it, expect } from 'vitest';
import { parseBondTent } from './parseBondTent';

describe('parseBondTent', () => {
    it('parseBondTent_malformedJson_returnsNull', () => {
        expect(parseBondTent('{not json')).toBeNull();
    });

    it('parseBondTent_nonObjectJson_returnsNull', () => {
        expect(parseBondTent('null')).toBeNull();
        expect(parseBondTent('42')).toBeNull();
    });

    it('parseBondTent_missingOrEmpty_returnsNull', () => {
        expect(parseBondTent(undefined)).toBeNull();
        expect(parseBondTent('')).toBeNull();
        expect(parseBondTent('{}')).toBeNull();
    });

    it('parseBondTent_withTent_returnsIt', () => {
        const tent = { coverage_years: 5 };

        expect(parseBondTent(JSON.stringify({ bond_tent: tent }))).toEqual(tent);
    });
});
