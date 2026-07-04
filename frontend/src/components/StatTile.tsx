import type { CSSProperties, ReactNode } from 'react';

interface StatTileProps {
    label: ReactNode;
    value: ReactNode;
    /** Color of the value line. Defaults to #444. */
    valueColor?: string;
    /** Native tooltip on the whole tile. */
    title?: string;
    /** Extra styles merged onto the value line (e.g. fontWeight, fontSize). */
    valueStyle?: CSSProperties;
}

const labelStyle: CSSProperties = { color: '#999', fontSize: '0.75rem', marginBottom: '0.15rem' };

/**
 * Label-over-value stat tile: a small muted label line above a value line.
 * The canonical card-detail tile used across list pages and chart summaries.
 */
export default function StatTile({ label, value, valueColor, title, valueStyle }: StatTileProps) {
    return (
        <div title={title}>
            <div style={labelStyle}>{label}</div>
            <div style={{ color: valueColor ?? '#444', ...valueStyle }}>{value}</div>
        </div>
    );
}
