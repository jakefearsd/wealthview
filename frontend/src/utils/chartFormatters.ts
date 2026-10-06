// formatDollarAxis is identical to shared formatCompactCurrency minus the nullish branch (chart
// axis tick values are always numbers, never null/undefined) — alias instead of reimplementing.
export { formatCompactCurrency as formatDollarAxis } from '@wealthview/shared';

/** Whole-dollar tooltip amount with the sign ahead of the symbol: -5000 -> "-$5,000". */
export const formatDollarTooltip = (value: number): string => {
    const whole = Math.round(Math.abs(value));
    const sign = value < 0 && whole > 0 ? '-' : '';
    return `${sign}$${whole.toLocaleString('en-US')}`;
};

export const formatPercentAxis = (value: number): string => `${value}%`;

/** Three-letter month abbreviations, 0-indexed (Jan = index 0). */
export const MONTH_ABBREVIATIONS = [
    'Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec',
];
