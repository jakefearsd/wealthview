import { useState } from 'react';
import {
    listStockSplits,
    createStockSplit,
    unapplyStockSplit,
    syncStockSplits,
} from '../../api/stockSplits';
import type { StockSplit } from '../../api/stockSplits';
import { useApiQuery } from '../../hooks/useApiQuery';
import { useApiMutation } from '../../hooks/useApiMutation';
import { cardStyle, inputStyle, tableStyle, thStyle, tdStyle } from '../../utils/styles';
import Button from '../Button';
import toast from 'react-hot-toast';

const sourceLabel: Record<StockSplit['source'], string> = {
    finnhub: 'Finnhub',
    yahoo: 'Yahoo',
    manual: 'Manual',
    backfill: 'Backfill',
};

export default function StockSplitsSection() {
    const { data: splits, refetch, loading } = useApiQuery(() => listStockSplits());
    const [form, setForm] = useState({
        symbol: '',
        effective_date: '',
        numerator: '4',
        denominator: '1',
    });
    const createMutation = useApiMutation(
        (input: { symbol: string; effective_date: string; numerator: number; denominator: number }) => createStockSplit(input),
        {
            successMessage: (_result, input) => `Split applied for ${input.symbol}`,
            onSuccess: () => {
                setForm({ symbol: '', effective_date: '', numerator: '4', denominator: '1' });
                refetch();
            },
        },
    );
    const submitting = createMutation.loading;

    function handleCreate(e: React.FormEvent) {
        e.preventDefault();
        const num = Number(form.numerator);
        const den = Number(form.denominator);
        if (!form.symbol.trim() || !form.effective_date || !num || !den) {
            toast.error('Fill every field');
            return;
        }
        void createMutation.mutate({
            symbol: form.symbol.trim().toUpperCase(),
            effective_date: form.effective_date,
            numerator: num,
            denominator: den,
        });
    }

    const unapplyMutation = useApiMutation(
        (id: string) => unapplyStockSplit(id),
        {
            successMessage: 'Split unapplied',
            onSuccess: () => refetch(),
        },
    );
    const unapplyingId = unapplyMutation.pendingInput;

    function handleUnapply(id: string, symbol: string, date: string) {
        if (!confirm(`Un-apply ${symbol} split on ${date}? Transactions and prices will be restored to their pre-split values.`)) {
            return;
        }
        void unapplyMutation.mutate(id);
    }

    const syncMutation = useApiMutation(
        () => syncStockSplits(),
        {
            successMessage: (result) => `Sync complete: ${result.splits_applied} new of ${result.splits_discovered} discovered (${result.symbols_scanned} symbols scanned)`,
            onSuccess: () => refetch(),
        },
    );
    const syncing = syncMutation.loading;

    function handleSync() {
        void syncMutation.mutate(undefined);
    }

    return (
        <div>
            <h2 style={{ marginBottom: '1.5rem' }}>Stock Splits</h2>

            <div style={{ ...cardStyle, marginBottom: '1.5rem' }}>
                <h3 style={{ marginTop: 0 }}>Sync from Finnhub</h3>
                <p style={{ color: '#666', fontSize: '0.9rem' }}>
                    Splits are detected automatically every night at 02:00. You can also trigger
                    a manual sync now.
                </p>
                <Button onClick={handleSync} disabled={syncing}>
                    {syncing ? 'Syncing...' : 'Sync now'}
                </Button>
            </div>

            <div style={{ ...cardStyle, marginBottom: '1.5rem' }}>
                <h3 style={{ marginTop: 0 }}>Add manual split</h3>
                <p style={{ color: '#666', fontSize: '0.9rem' }}>
                    Use this when Finnhub doesn't cover the symbol or you need to record a split
                    historically. Numerator:denominator means new-shares-per-old-shares — a 4:1
                    AAPL split is numerator=4, denominator=1.
                </p>
                <form onSubmit={handleCreate} style={{ display: 'grid', gap: '0.75rem', gridTemplateColumns: '1fr 1fr 1fr 1fr auto' }}>
                    <input
                        style={inputStyle}
                        placeholder="Symbol (e.g. AAPL)"
                        value={form.symbol}
                        onChange={(e) => setForm({ ...form, symbol: e.target.value })}
                    />
                    <input
                        type="date"
                        style={inputStyle}
                        value={form.effective_date}
                        onChange={(e) => setForm({ ...form, effective_date: e.target.value })}
                    />
                    <input
                        type="number"
                        min={1}
                        style={inputStyle}
                        placeholder="Numerator"
                        value={form.numerator}
                        onChange={(e) => setForm({ ...form, numerator: e.target.value })}
                    />
                    <input
                        type="number"
                        min={1}
                        style={inputStyle}
                        placeholder="Denominator"
                        value={form.denominator}
                        onChange={(e) => setForm({ ...form, denominator: e.target.value })}
                    />
                    <Button type="submit" disabled={submitting}>
                        {submitting ? 'Adding...' : 'Add split'}
                    </Button>
                </form>
            </div>

            <div style={cardStyle}>
                <h3 style={{ marginTop: 0 }}>Applied splits</h3>
                {loading && <p style={{ color: '#666' }}>Loading...</p>}
                {!loading && splits && splits.length === 0 && (
                    <p style={{ color: '#666' }}>No splits have been applied yet.</p>
                )}
                {!loading && splits && splits.length > 0 && (
                    <table style={tableStyle}>
                        <thead>
                            <tr>
                                <th style={thStyle}>Symbol</th>
                                <th style={thStyle}>Effective date</th>
                                <th style={thStyle}>Ratio</th>
                                <th style={thStyle}>Source</th>
                                <th style={thStyle}>Applied</th>
                                <th style={thStyle}>Actions</th>
                            </tr>
                        </thead>
                        <tbody>
                            {splits.map((s) => (
                                <tr key={s.id}>
                                    <td style={tdStyle}>{s.symbol}</td>
                                    <td style={tdStyle}>{s.effective_date}</td>
                                    <td style={tdStyle}>{s.numerator}:{s.denominator}</td>
                                    <td style={tdStyle}>{sourceLabel[s.source]}</td>
                                    <td style={tdStyle}>{new Date(s.applied_at).toLocaleString()}</td>
                                    <td style={tdStyle}>
                                        <Button
                                            onClick={() => handleUnapply(s.id, s.symbol, s.effective_date)}
                                            disabled={unapplyingId === s.id}
                                            variant="secondary"
                                        >
                                            {unapplyingId === s.id ? 'Reverting...' : 'Un-apply'}
                                        </Button>
                                    </td>
                                </tr>
                            ))}
                        </tbody>
                    </table>
                )}
            </div>
        </div>
    );
}
