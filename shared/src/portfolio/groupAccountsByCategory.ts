import type { AccountResponse } from '../api/types';

export type AccountCategory = 'investment' | 'cash' | 'other';

export interface AccountGroup {
    /** Stable machine-readable identifier (used for keys, ordering). */
    category: AccountCategory;
    /** Human-readable section header text. */
    label: string;
    /** Accounts in this group, sorted by balance descending. */
    accounts: AccountResponse[];
    /** Sum of balances in the group, as a decimal string. */
    total: string;
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
 * Compares two decimal strings ("123.45" vs "67.8") numerically WITHOUT
 * going through Number — preserves precision on values that exceed
 * Number.MAX_SAFE_INTEGER. Returns a negative number if a < b, positive
 * if a > b, zero if equal.
 */
function compareDecimal(a: string, b: string): number {
    const [aSign, aAbs] = splitSign(a);
    const [bSign, bAbs] = splitSign(b);
    if (aSign !== bSign) return aSign - bSign;
    const cmp = compareAbsDecimal(aAbs, bAbs);
    return aSign < 0 ? -cmp : cmp;
}

function splitSign(s: string): [number, string] {
    if (s.startsWith('-')) return [-1, s.slice(1)];
    return [1, s];
}

function compareAbsDecimal(a: string, b: string): number {
    const [ai, af] = splitDecimal(a);
    const [bi, bf] = splitDecimal(b);
    // Strip leading zeros from integer parts so length comparison is meaningful.
    const aii = ai.replace(/^0+/, '') || '0';
    const bii = bi.replace(/^0+/, '') || '0';
    if (aii.length !== bii.length) return aii.length - bii.length;
    if (aii !== bii) return aii < bii ? -1 : 1;
    // Pad fractional parts to equal length for lexicographic compare.
    const maxLen = Math.max(af.length, bf.length);
    const afp = af.padEnd(maxLen, '0');
    const bfp = bf.padEnd(maxLen, '0');
    if (afp === bfp) return 0;
    return afp < bfp ? -1 : 1;
}

function splitDecimal(s: string): [string, string] {
    const i = s.indexOf('.');
    if (i < 0) return [s, ''];
    return [s.slice(0, i), s.slice(i + 1)];
}

/**
 * Sums an array of decimal strings exactly. Implemented by aligning
 * fractional places and doing schoolbook addition on the integer
 * representation, then re-inserting the decimal point. Avoids
 * floating-point error for the kind of money values we display.
 */
function sumDecimals(values: string[]): string {
    if (values.length === 0) return '0';
    // Find max fractional digits.
    let maxFrac = 0;
    for (const v of values) {
        const [, frac] = splitDecimal(v.startsWith('-') ? v.slice(1) : v);
        if (frac.length > maxFrac) maxFrac = frac.length;
    }
    // Convert each value to a BigInt scaled by 10^maxFrac.
    let acc = 0n;
    const scale = 10n ** BigInt(maxFrac);
    for (const v of values) {
        const negative = v.startsWith('-');
        const [intPart, fracPart] = splitDecimal(negative ? v.slice(1) : v);
        const fracPadded = fracPart.padEnd(maxFrac, '0');
        const combined = `${intPart || '0'}${fracPadded}`;
        const n = BigInt(combined);
        acc += negative ? -n : n;
    }
    if (maxFrac === 0) return acc.toString();
    const negative = acc < 0n;
    const abs = negative ? -acc : acc;
    const absStr = abs.toString().padStart(maxFrac + 1, '0');
    const intPart = absStr.slice(0, absStr.length - maxFrac);
    const fracPart = absStr.slice(absStr.length - maxFrac);
    const result = `${intPart}.${fracPart}`;
    void scale; // keep for clarity; unused after refactor
    return negative ? `-${result}` : result;
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
        const sorted = [...list].sort((a, b) => compareDecimal(b.balance, a.balance));
        groups.push({
            category: cat,
            label: LABELS[cat],
            accounts: sorted,
            total: sumDecimals(sorted.map((a) => a.balance)),
        });
    }
    return groups;
}
