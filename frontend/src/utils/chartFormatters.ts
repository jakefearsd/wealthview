export const formatDollarAxis = (value: number): string => {
    const abs = Math.abs(value);
    const sign = value < 0 ? '-' : '';
    if (abs >= 1_000_000) return `${sign}$${(abs / 1_000_000).toFixed(1)}M`;
    if (abs >= 1_000) return `${sign}$${(abs / 1_000).toFixed(0)}k`;
    return `${sign}$${Math.round(abs)}`;
};

export const formatDollarTooltip = (value: number): string =>
    `$${value.toLocaleString('en-US', { maximumFractionDigits: 0 })}`;

export const formatPercentAxis = (value: number): string => `${value}%`;
