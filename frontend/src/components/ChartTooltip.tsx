import React from 'react';

interface ChartTooltipProps {
    active?: boolean;
    payload?: any[];
    label?: any;
    renderContent: (label: any, payload: any[]) => React.ReactNode | null;
}

export default function ChartTooltip({ active, payload, label, renderContent }: ChartTooltipProps) {
    if (!active || !payload?.length) return null;
    const content = renderContent(label, payload);
    if (!content) return null;
    return (
        <div style={{
            background: '#fff',
            border: '1px solid #ccc',
            padding: '0.75rem',
            borderRadius: 4,
            fontSize: '0.85rem',
        }}>
            {content}
        </div>
    );
}
