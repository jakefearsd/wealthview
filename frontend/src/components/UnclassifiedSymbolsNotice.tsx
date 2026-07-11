import { useState } from 'react';
import toast from 'react-hot-toast';
import { setClassification } from '../api/securities';
import { extractErrorMessage } from '../utils/errorMessage';
import { selectStyle } from '../utils/styles';

interface Props {
    /** Symbols the projection defaulted to US Stock because no classification exists yet. */
    symbols: string[];
    /** Invoked once every selected symbol has been persisted — caller should re-run the projection. */
    onReclassified: () => void;
}

const ASSET_CLASS_OPTIONS: { value: string; label: string }[] = [
    { value: 'us_stock', label: 'US Stock' },
    { value: 'intl_stock', label: 'Intl Stock' },
    { value: 'bond', label: 'Bonds' },
    { value: 'cash', label: 'Cash' },
];

/**
 * Orange notice shown above the projection results when the run reported
 * holdings with no asset-class classification. Lets the user pick the
 * correct class per symbol, persists the choices, then re-runs the
 * projection via {@link onReclassified}.
 */
export default function UnclassifiedSymbolsNotice({ symbols, onReclassified }: Props) {
    const [selections, setSelections] = useState<Record<string, string>>({});
    const [applying, setApplying] = useState(false);
    const [error, setError] = useState<string | null>(null);

    const chosenSymbols = symbols.filter(symbol => selections[symbol]);
    const canApply = !applying && chosenSymbols.length > 0;

    const handleSelect = (symbol: string, assetClass: string) => {
        setSelections(prev => ({ ...prev, [symbol]: assetClass }));
    };

    const handleApply = async () => {
        if (chosenSymbols.length === 0) return;

        setApplying(true);
        setError(null);
        try {
            await Promise.all(chosenSymbols.map(symbol => setClassification(symbol, selections[symbol])));
            onReclassified();
        } catch (err) {
            const message = extractErrorMessage(err);
            setError(message);
            toast.error(message);
        } finally {
            setApplying(false);
        }
    };

    return (
        <div style={{
            background: '#fff3e0', borderLeft: '4px solid #e65100', padding: '1rem',
            marginBottom: '1rem', borderRadius: '4px',
        }}>
            <strong>These holdings were modeled as US Stock because we couldn&apos;t classify them:</strong>
            <div style={{ marginTop: '0.75rem', display: 'flex', flexDirection: 'column', gap: '0.5rem' }}>
                {symbols.map(symbol => (
                    <div key={symbol} style={{ display: 'flex', alignItems: 'center', gap: '0.75rem' }}>
                        <span style={{ fontFamily: 'monospace', minWidth: '5rem' }}>{symbol}</span>
                        <select
                            aria-label={`Asset class for ${symbol}`}
                            style={selectStyle}
                            value={selections[symbol] ?? ''}
                            onChange={(e) => handleSelect(symbol, e.target.value)}
                        >
                            <option value="">Select...</option>
                            {ASSET_CLASS_OPTIONS.map(opt => (
                                <option key={opt.value} value={opt.value}>{opt.label}</option>
                            ))}
                        </select>
                    </div>
                ))}
            </div>
            {error && (
                <div role="alert" style={{ marginTop: '0.75rem', color: '#c62828', fontSize: '0.85rem' }}>
                    {error}
                </div>
            )}
            <button
                type="button"
                onClick={() => void handleApply()}
                disabled={!canApply}
                style={{
                    marginTop: '0.75rem', padding: '0.5rem 1rem', borderRadius: '4px', border: 'none',
                    background: '#e65100', color: '#fff', fontWeight: 600,
                    cursor: canApply ? 'pointer' : 'not-allowed', opacity: canApply ? 1 : 0.6,
                }}
            >
                {applying ? 'Applying...' : 'Apply & re-run'}
            </button>
        </div>
    );
}
