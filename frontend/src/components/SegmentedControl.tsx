import type { CSSProperties } from 'react';

export interface SegmentedControlOption<T extends string> {
    value: T;
    label: string;
}

interface SegmentedControlProps<T extends string> {
    options: SegmentedControlOption<T>[];
    value: T;
    onChange: (value: T) => void;
    /**
     * 'compact' (default) matches the small period-selector tabs used on charts (auto-width
     * pills, divider lines between them). 'wide' gives every option equal flex width with
     * larger text and no dividers — used for form-field pickers like Risk Tolerance. This
     * preserves each adopted site's existing visual treatment rather than forcing an
     * unflagged style convergence.
     */
    variant?: 'compact' | 'wide';
}

const compactContainerStyle: CSSProperties = {
    display: 'flex',
    borderRadius: 6,
    overflow: 'hidden',
    border: '1px solid #ddd',
};

const wideContainerStyle: CSSProperties = {
    display: 'flex',
    borderRadius: 4,
    overflow: 'hidden',
    border: '1px solid #ccc',
};

function optionStyle(active: boolean, variant: 'compact' | 'wide'): CSSProperties {
    if (variant === 'wide') {
        return {
            flex: 1,
            padding: '0.5rem 0.75rem',
            fontSize: '0.85rem',
            border: 'none',
            background: active ? '#1976d2' : '#fff',
            color: active ? '#fff' : '#333',
            cursor: 'pointer',
            fontWeight: active ? 600 : 400,
            textTransform: 'capitalize',
            transition: 'background 0.15s, color 0.15s',
        };
    }
    return {
        padding: '0.3rem 0.65rem',
        fontSize: '0.75rem',
        border: 'none',
        borderRight: '1px solid #ddd',
        background: active ? '#1976d2' : '#fff',
        color: active ? '#fff' : '#555',
        cursor: 'pointer',
        fontWeight: active ? 600 : 400,
    };
}

/** A row of mutually-exclusive pill buttons — the "blue-pill" selector pattern used across
 * chart period pickers and form-field choices. */
export default function SegmentedControl<T extends string>({
    options, value, onChange, variant = 'compact',
}: SegmentedControlProps<T>) {
    return (
        <div style={variant === 'wide' ? wideContainerStyle : compactContainerStyle}>
            {options.map(opt => (
                <button
                    key={opt.value}
                    type="button"
                    onClick={() => onChange(opt.value)}
                    style={optionStyle(value === opt.value, variant)}
                >
                    {opt.label}
                </button>
            ))}
        </div>
    );
}
