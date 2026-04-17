import React from 'react';
import type { RechartsTooltipEntry, RechartsTooltipProps } from '../types/recharts';
import { tooltipStyle } from '../utils/styles';

interface ChartTooltipProps<T = Record<string, unknown>> extends RechartsTooltipProps<T> {
    renderContent: (label: string | number | undefined, payload: Array<RechartsTooltipEntry<T>>) => React.ReactNode | null;
}

export default function ChartTooltip<T = Record<string, unknown>>({ active, payload, label, renderContent }: ChartTooltipProps<T>) {
    if (!active || !payload?.length) return null;
    const content = renderContent(label, payload);
    if (!content) return null;
    return <div style={tooltipStyle}>{content}</div>;
}
