import type { AllocationInput } from '../types/projection';
import { labelStyle, inputFieldStyle } from '../utils/styles';

interface AllocationEditorProps {
    value: AllocationInput;
    onChange: (value: AllocationInput) => void;
    onReset?: () => void;
}

const FIELDS: { key: keyof AllocationInput; label: string }[] = [
    { key: 'us_stock', label: 'US Stocks' },
    { key: 'intl_stock', label: 'Intl Stocks' },
    { key: 'bond', label: 'Bonds' },
    { key: 'cash', label: 'Cash' },
];

const ALLOCATION_SUM_TOLERANCE = 0.01;
const ALLOCATION_TARGET_SUM = 100;

export function allocationSum(value: AllocationInput): number {
    return value.us_stock + value.intl_stock + value.bond + value.cash;
}

export function isAllocationValid(value: AllocationInput | null): boolean {
    if (value === null) {
        return true;
    }
    return Math.abs(allocationSum(value) - ALLOCATION_TARGET_SUM) <= ALLOCATION_SUM_TOLERANCE;
}

const rowStyle: React.CSSProperties = {
    display: 'grid',
    gridTemplateColumns: 'repeat(4, 1fr)',
    gap: '0.75rem',
};

const totalRowStyle: React.CSSProperties = {
    marginTop: '0.5rem',
    fontSize: '0.85rem',
    fontWeight: 600,
};

const invalidTotalStyle: React.CSSProperties = {
    ...totalRowStyle,
    color: '#d32f2f',
};

const resetButtonStyle: React.CSSProperties = {
    marginTop: '0.5rem',
    padding: '0.25rem 0.6rem',
    background: 'none',
    border: '1px solid #999',
    borderRadius: '4px',
    color: '#555',
    cursor: 'pointer',
    fontSize: '0.8rem',
};

export default function AllocationEditor({ value, onChange, onReset }: AllocationEditorProps) {
    const sum = allocationSum(value);
    const valid = isAllocationValid(value);

    function handleFieldChange(key: keyof AllocationInput, raw: string) {
        onChange({ ...value, [key]: Number(raw) });
    }

    return (
        <div>
            <div style={rowStyle}>
                {FIELDS.map(({ key, label }) => (
                    <div key={key}>
                        <label style={labelStyle} htmlFor={`allocation-${key}`}>{label} (%)</label>
                        <input
                            id={`allocation-${key}`}
                            style={inputFieldStyle}
                            type="number"
                            step="0.1"
                            value={value[key]}
                            onChange={e => handleFieldChange(key, e.target.value)}
                        />
                    </div>
                ))}
            </div>
            <div style={valid ? totalRowStyle : invalidTotalStyle}>
                Total: {sum}%
                {!valid && <span> — must sum to 100%</span>}
            </div>
            {onReset && (
                <button type="button" style={resetButtonStyle} onClick={onReset}>
                    Reset to derived
                </button>
            )}
        </div>
    );
}
