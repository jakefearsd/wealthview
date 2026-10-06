const ACCOUNT_TYPE_LABELS: Record<string, string> = {
    '401k': '401(k)',
    ira: 'IRA',
    roth: 'Roth IRA',
    brokerage: 'Brokerage',
    bank: 'Bank',
};

/** Upper-cases the first character ("buy" becomes "Buy"); the rest is left untouched. */
export function capitalize(value: string): string {
    return value.charAt(0).toUpperCase() + value.slice(1);
}

/** Display label for an account type enum value, shared so every page names an account type the same way. */
export function accountTypeLabel(type: string): string {
    return ACCOUNT_TYPE_LABELS[type] ?? capitalize(type);
}
