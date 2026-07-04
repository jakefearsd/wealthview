import type { CSSProperties } from 'react';

interface TabBarProps<K extends string> {
    tabs: ReadonlyArray<{ key: K; label: string }>;
    active: K;
    onSelect: (key: K) => void;
    /** Extra styles merged onto the container (e.g. marginBottom). */
    style?: CSSProperties;
}

function tabButtonStyle(active: boolean): CSSProperties {
    return {
        padding: '0.5rem 1rem',
        background: 'none',
        border: 'none',
        borderBottom: `2px solid ${active ? '#1976d2' : 'transparent'}`,
        color: active ? '#1976d2' : '#666',
        fontWeight: active ? 600 : 400,
        cursor: 'pointer',
        fontSize: '0.95rem',
    };
}

/**
 * Horizontal row of underline-style tab buttons over a light divider.
 * Purely presentational — the caller owns the active-tab state.
 */
export default function TabBar<K extends string>({ tabs, active, onSelect, style }: TabBarProps<K>) {
    return (
        <div style={{ display: 'flex', gap: '0.25rem', borderBottom: '1px solid #e0e0e0', ...style }}>
            {tabs.map((tab) => (
                <button key={tab.key} style={tabButtonStyle(tab.key === active)} onClick={() => onSelect(tab.key)}>
                    {tab.label}
                </button>
            ))}
        </div>
    );
}
