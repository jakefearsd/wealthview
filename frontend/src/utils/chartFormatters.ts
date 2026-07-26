// formatDollarAxis is identical to shared formatCompactCurrency minus the nullish branch (chart
// axis tick values are always numbers, never null/undefined) — alias instead of reimplementing.
export { formatCompactCurrency as formatDollarAxis } from '@wealthview/shared';

export const formatDollarTooltip = (value: number): string =>
    `$${value.toLocaleString('en-US', { maximumFractionDigits: 0 })}`;

export const formatPercentAxis = (value: number): string => `${value}%`;
