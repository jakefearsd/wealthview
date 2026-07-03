import type { AccountResponse } from '../api/types';

export type AccountCategory = 'investment' | 'cash' | 'other';

export interface AccountGroup {
    /** Stable machine-readable identifier (used for keys, ordering). */
    category: AccountCategory;
    /** Human-readable section header text. */
    label: string;
    /** Accounts in this group, sorted by balance descending. */
    accounts: AccountResponse[];
    /** Sum of balances in the group. */
    total: number;
}

const CATEGORY_ORDER: readonly AccountCategory[] = ['investment', 'cash', 'other'];

const LABELS: Record<AccountCategory, string> = {
    investment: 'Investment Accounts',
    cash: 'Cash',
    other: 'Other',
};

function categorize(type: string): AccountCategory {
    switch (type) {
        case 'brokerage':
        case 'ira':
        case '401k':
        case 'roth':
            return 'investment';
        case 'bank':
            return 'cash';
        default:
            return 'other';
    }
}

/**
 * Groups accounts into stable, ordered categories for display on the
 * Portfolio screen. Investment-style accounts come first (because they're
 * usually the bulk of net worth), then cash, then anything else. Within
 * each group, the largest balance comes first.
 *
 * Pure: no React/RN imports, no globals, deterministic for any input.
 */
export function groupAccountsByCategory(accounts: AccountResponse[]): AccountGroup[] {
    const buckets = new Map<AccountCategory, AccountResponse[]>();
    for (const acct of accounts) {
        const cat = categorize(acct.type);
        const list = buckets.get(cat) ?? [];
        list.push(acct);
        buckets.set(cat, list);
    }
    const groups: AccountGroup[] = [];
    for (const cat of CATEGORY_ORDER) {
        const list = buckets.get(cat);
        if (!list || list.length === 0) continue;
        const sorted = [...list].sort((a, b) => b.balance - a.balance);
        groups.push({
            category: cat,
            label: LABELS[cat],
            accounts: sorted,
            total: sorted.reduce((sum, a) => sum + a.balance, 0),
        });
    }
    return groups;
}
