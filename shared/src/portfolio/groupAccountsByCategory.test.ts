import { describe, it, expect } from 'vitest';
import { groupAccountsByCategory } from './groupAccountsByCategory';
import type { AccountResponse } from '../api/types';

function account(overrides: Partial<AccountResponse>): AccountResponse {
    return {
        id: overrides.id ?? 'id',
        name: overrides.name ?? 'name',
        type: overrides.type ?? 'brokerage',
        institution: overrides.institution ?? null,
        currency: overrides.currency ?? 'USD',
        balance: overrides.balance ?? 0,
        created_at: overrides.created_at ?? '2026-01-01T00:00:00Z',
    };
}

describe('groupAccountsByCategory', () => {
    it('returns an empty array for no accounts', () => {
        expect(groupAccountsByCategory([])).toEqual([]);
    });

    it('returns one group with one account when given a single account', () => {
        const a = account({ id: 'a1', type: 'brokerage', balance: 1000 });

        const groups = groupAccountsByCategory([a]);

        expect(groups).toHaveLength(1);
        expect(groups[0].category).toBe('investment');
        expect(groups[0].accounts).toEqual([a]);
        expect(groups[0].total).toBe(1000);
    });

    it('groups brokerage and retirement (ira/401k/roth) under "investment" and bank under "cash"', () => {
        const brokerage = account({ id: 'b', type: 'brokerage', balance: 100 });
        const ira = account({ id: 'i', type: 'ira', balance: 200 });
        const fourOhOneK = account({ id: 'k', type: '401k', balance: 300 });
        const roth = account({ id: 'r', type: 'roth', balance: 400 });
        const bank = account({ id: 'c', type: 'bank', balance: 50 });

        const groups = groupAccountsByCategory([brokerage, ira, fourOhOneK, roth, bank]);

        const investment = groups.find((g) => g.category === 'investment');
        const cash = groups.find((g) => g.category === 'cash');
        expect(investment).toBeTruthy();
        expect(cash).toBeTruthy();
        expect(investment!.accounts).toHaveLength(4);
        expect(cash!.accounts).toEqual([bank]);
        expect(investment!.total).toBe(1000);
        expect(cash!.total).toBe(50);
    });

    it('orders categories investment first, then cash, then anything else', () => {
        const bank = account({ id: 'c', type: 'bank', balance: 50 });
        const brokerage = account({ id: 'b', type: 'brokerage', balance: 100 });
        const unknown = account({ id: 'u', type: 'crypto', balance: 10 });

        const groups = groupAccountsByCategory([unknown, bank, brokerage]);

        expect(groups.map((g) => g.category)).toEqual(['investment', 'cash', 'other']);
    });

    it('within a group, sorts accounts by balance descending', () => {
        const small = account({ id: 's', type: 'brokerage', balance: 100 });
        const huge = account({ id: 'h', type: 'brokerage', balance: 999999 });
        const medium = account({ id: 'm', type: 'brokerage', balance: 5000 });

        const [investment] = groupAccountsByCategory([small, huge, medium]);

        expect(investment.accounts.map((a) => a.id)).toEqual(['h', 'm', 's']);
    });

    it('sorts negative balances below positive ones', () => {
        const overdrawn = account({ id: 'o', type: 'bank', balance: -250.5 });
        const positive = account({ id: 'p', type: 'bank', balance: 10 });

        const [cash] = groupAccountsByCategory([overdrawn, positive]);

        expect(cash.accounts.map((a) => a.id)).toEqual(['p', 'o']);
        expect(cash.total).toBeCloseTo(-240.5, 10);
    });

    it('falls back to an "other" group for unknown account types', () => {
        const a = account({ id: 'x', type: 'crypto', balance: 42 });

        const groups = groupAccountsByCategory([a]);

        expect(groups).toHaveLength(1);
        expect(groups[0].category).toBe('other');
        expect(groups[0].label).toMatch(/other/i);
        expect(groups[0].accounts).toEqual([a]);
    });

    it('sums fractional balances', () => {
        const a = account({ id: 'a', type: 'brokerage', balance: 0.1 });
        const b = account({ id: 'b', type: 'brokerage', balance: 0.2 });

        const [investment] = groupAccountsByCategory([a, b]);

        expect(investment.total).toBeCloseTo(0.3, 10);
    });

    it('exposes a human-readable label for each group', () => {
        const groups = groupAccountsByCategory([
            account({ id: 'a', type: 'brokerage', balance: 1 }),
            account({ id: 'b', type: 'bank', balance: 1 }),
        ]);

        const labels = groups.map((g) => g.label);
        expect(labels).toContain('Investment Accounts');
        expect(labels).toContain('Cash');
    });
});
