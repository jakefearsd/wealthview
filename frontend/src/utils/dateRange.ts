export interface MonthRange {
    from: string;
    to: string;
}

function formatYearMonth(date: Date): string {
    return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}`;
}

/**
 * The 12 calendar months ending at the given date's month (inclusive),
 * as YYYY-MM strings. Date arithmetic handles year rollover.
 */
export function trailingTwelveMonthRange(now: Date = new Date()): MonthRange {
    const from = new Date(now.getFullYear(), now.getMonth() - 11, 1);
    return { from: formatYearMonth(from), to: formatYearMonth(now) };
}
