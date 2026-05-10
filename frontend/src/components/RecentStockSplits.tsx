import { useApiQuery } from '../hooks/useApiQuery';
import { listStockSplits } from '../api/stockSplits';
import type { StockSplit } from '../api/stockSplits';
import { cardStyle, tableStyle, thStyle, tdStyle } from '../utils/styles';

interface Props {
    /** Optional symbol filter — when set, only that symbol's splits are shown. */
    symbol?: string;
    /** Optional title override (default: "Recent stock splits"). */
    title?: string;
}

const sourceLabel: Record<StockSplit['source'], string> = {
    finnhub: 'Auto-detected via Finnhub',
    yahoo: 'Auto-detected via Yahoo',
    manual: 'Manually entered',
    backfill: 'Backfilled',
};

const badgeColor: Record<StockSplit['source'], string> = {
    finnhub: '#1976d2',
    yahoo: '#7b1fa2',
    manual: '#ef6c00',
    backfill: '#558b2f',
};

/**
 * Read-only widget showing recent stock splits affecting the user's
 * portfolio. Drop into Dashboard, settings, or a holding-detail page.
 */
export default function RecentStockSplits({ symbol, title }: Props) {
    const { data: splits, loading, error } = useApiQuery(() =>
        listStockSplits(symbol ? { symbol } : undefined)
    );

    return (
        <div style={cardStyle}>
            <h3 style={{ marginTop: 0, marginBottom: '1rem' }}>
                {title ?? 'Recent stock splits'}
            </h3>
            {loading && <p style={{ color: '#666' }}>Loading...</p>}
            {error && <p style={{ color: '#c62828' }}>Failed to load: {error}</p>}
            {!loading && !error && splits && splits.length === 0 && (
                <p style={{ color: '#666' }}>
                    No stock splits affect {symbol ? symbol : 'your portfolio'} yet.
                    {' '}New splits are detected automatically each night.
                </p>
            )}
            {!loading && !error && splits && splits.length > 0 && (
                <table style={tableStyle}>
                    <thead>
                        <tr>
                            <th style={thStyle}>Symbol</th>
                            <th style={thStyle}>Effective date</th>
                            <th style={thStyle}>Ratio</th>
                            <th style={thStyle}>Source</th>
                        </tr>
                    </thead>
                    <tbody>
                        {splits.map((s) => (
                            <tr key={s.id}>
                                <td style={tdStyle}>{s.symbol}</td>
                                <td style={tdStyle}>{s.effective_date}</td>
                                <td style={tdStyle}>{s.numerator}:{s.denominator}</td>
                                <td style={tdStyle}>
                                    <span style={{
                                        background: badgeColor[s.source],
                                        color: '#fff',
                                        padding: '0.15rem 0.5rem',
                                        borderRadius: '3px',
                                        fontSize: '0.75rem',
                                    }}>
                                        {sourceLabel[s.source]}
                                    </span>
                                </td>
                            </tr>
                        ))}
                    </tbody>
                </table>
            )}
        </div>
    );
}
