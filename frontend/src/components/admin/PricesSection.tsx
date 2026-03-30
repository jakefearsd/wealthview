import { useState, useRef } from 'react';
import {
    getPriceStatus,
    syncFinnhub,
    syncYahoo,
    fetchYahoo,
    saveYahooPrices,
    uploadPriceCsv,
} from '../../api/adminPrices';
import type { PriceSyncStatus, PriceEntry } from '../../api/adminPrices';
import { useApiQuery } from '../../hooks/useApiQuery';
import { cardStyle, tableStyle, thStyle, tdStyle, trHoverStyle } from '../../utils/styles';
import { formatCurrency } from '../../utils/format';
import PriceBrowserTab from './PriceBrowserTab';
import Button from '../Button';
import toast from 'react-hot-toast';

type TabId = 'finnhub' | 'yahoo' | 'csv' | 'browse';

const tabButtonStyle = (active: boolean) => ({
    padding: '0.5rem 1rem',
    background: 'none',
    border: 'none',
    borderBottom: `2px solid ${active ? '#1976d2' : 'transparent'}`,
    color: active ? '#1976d2' : '#666',
    fontWeight: active ? 600 as const : 400 as const,
    cursor: 'pointer' as const,
    fontSize: '0.95rem',
});

function todayStr(): string {
    return new Date().toISOString().slice(0, 10);
}

function thirtyDaysAgoStr(): string {
    const d = new Date();
    d.setDate(d.getDate() - 30);
    return d.toISOString().slice(0, 10);
}

export default function PricesSection() {
    const [activeTab, setActiveTab] = useState<TabId>('finnhub');

    return (
        <div>
            <h2 style={{ marginBottom: '1.5rem' }}>Prices</h2>

            <div style={{ display: 'flex', gap: '0.25rem', marginBottom: '1.5rem', borderBottom: '1px solid #e0e0e0' }}>
                <button style={tabButtonStyle(activeTab === 'finnhub')} onClick={() => setActiveTab('finnhub')}>
                    Finnhub Sync
                </button>
                <button style={tabButtonStyle(activeTab === 'yahoo')} onClick={() => setActiveTab('yahoo')}>
                    Yahoo Finance
                </button>
                <button style={tabButtonStyle(activeTab === 'csv')} onClick={() => setActiveTab('csv')}>
                    CSV Upload
                </button>
                <button style={tabButtonStyle(activeTab === 'browse')} onClick={() => setActiveTab('browse')}>
                    Browse
                </button>
            </div>

            {activeTab === 'finnhub' && <FinnhubTab />}
            {activeTab === 'yahoo' && <YahooTab />}
            {activeTab === 'csv' && <CsvTab />}
            {activeTab === 'browse' && <PriceBrowserTab />}
        </div>
    );
}

function FinnhubTab() {
    const { data: statuses, loading, refetch } = useApiQuery(getPriceStatus);
    const [syncing, setSyncing] = useState(false);

    async function handleSync() {
        setSyncing(true);
        try {
            await syncFinnhub();
            toast.success('Finnhub sync complete');
            refetch();
        } catch {
            toast.error('Finnhub sync failed');
        } finally {
            setSyncing(false);
        }
    }

    return (
        <div>
            <div style={{ ...cardStyle, marginBottom: '1.5rem' }}>
                <div style={{ display: 'flex', alignItems: 'center', gap: '1rem' }}>
                    <Button onClick={handleSync} disabled={syncing}>
                        {syncing ? 'Syncing...' : 'Sync All Holdings'}
                    </Button>
                    {syncing && <span style={{ color: '#666', fontSize: '0.9rem' }}>Fetching latest prices from Finnhub...</span>}
                </div>
            </div>

            <div style={cardStyle}>
                <h3 style={{ marginBottom: '1rem' }}>Price Sync Status</h3>
                {loading ? (
                    <div style={{ color: '#666' }}>Loading...</div>
                ) : (
                    <table style={tableStyle}>
                        <thead>
                            <tr>
                                <th style={thStyle}>Symbol</th>
                                <th style={thStyle}>Latest Date</th>
                                <th style={thStyle}>Source</th>
                                <th style={{ ...thStyle, textAlign: 'center' }}>Status</th>
                            </tr>
                        </thead>
                        <tbody>
                            {statuses?.map((s: PriceSyncStatus) => (
                                <tr key={s.symbol} style={trHoverStyle}>
                                    <td style={{ ...tdStyle, fontWeight: 600 }}>{s.symbol}</td>
                                    <td style={{ ...tdStyle, color: '#555' }}>{s.latest_date ?? '—'}</td>
                                    <td style={{ ...tdStyle, color: '#555' }}>{s.source ?? '—'}</td>
                                    <td style={{ ...tdStyle, textAlign: 'center' }}>
                                        <span style={{
                                            padding: '0.2rem 0.6rem',
                                            borderRadius: '4px',
                                            fontSize: '0.8rem',
                                            background: s.stale ? '#ffebee' : '#e8f5e9',
                                            color: s.stale ? '#c62828' : '#2e7d32',
                                        }}>
                                            {s.stale ? 'Stale' : 'Current'}
                                        </span>
                                    </td>
                                </tr>
                            ))}
                            {statuses?.length === 0 && (
                                <tr>
                                    <td colSpan={4} style={{ padding: '1rem', color: '#999', textAlign: 'center' }}>
                                        No price data found
                                    </td>
                                </tr>
                            )}
                        </tbody>
                    </table>
                )}
            </div>
        </div>
    );
}

function YahooTab() {
    const [syncingAll, setSyncingAll] = useState(false);
    const [symbolInput, setSymbolInput] = useState('');
    const [fromDate, setFromDate] = useState(thirtyDaysAgoStr());
    const [toDate, setToDate] = useState(todayStr());
    const [fetching, setFetching] = useState(false);
    const [preview, setPreview] = useState<PriceEntry[] | null>(null);
    const [saving, setSaving] = useState(false);

    async function handleSyncAll() {
        setSyncingAll(true);
        try {
            const result = await syncYahoo();
            const failedMsg = result.failed.length > 0 ? ` Failed: ${result.failed.join(', ')}.` : '';
            toast.success(`Inserted ${result.inserted}, updated ${result.updated}.${failedMsg}`);
        } catch {
            toast.error('Yahoo sync failed');
        } finally {
            setSyncingAll(false);
        }
    }

    async function handleFetchPreview() {
        const symbols = symbolInput
            .split(',')
            .map((s) => s.trim().toUpperCase())
            .filter((s) => s.length > 0);

        if (symbols.length === 0) {
            toast.error('Enter at least one symbol');
            return;
        }

        setFetching(true);
        setPreview(null);
        try {
            const prices = await fetchYahoo({ symbols, from_date: fromDate, to_date: toDate });
            setPreview(prices);
            if (prices.length === 0) toast('No prices returned for those symbols and dates');
        } catch {
            toast.error('Fetch failed');
        } finally {
            setFetching(false);
        }
    }

    async function handleSaveAll() {
        if (!preview || preview.length === 0) return;
        setSaving(true);
        try {
            await saveYahooPrices(preview);
            toast.success(`Saved ${preview.length} price entries`);
            setPreview(null);
        } catch {
            toast.error('Save failed');
        } finally {
            setSaving(false);
        }
    }

    return (
        <div>
            <div style={{
                background: '#fff3e0',
                border: '1px solid #ff9800',
                borderRadius: '4px',
                padding: '0.75rem 1rem',
                marginBottom: '1.5rem',
                color: '#e65100',
                fontSize: '0.9rem',
            }}>
                Yahoo Finance scraping may break without notice. Use as a fallback for symbols Finnhub doesn't cover.
            </div>

            <div style={{ ...cardStyle, marginBottom: '1.5rem' }}>
                <h3 style={{ marginBottom: '1rem' }}>Sync All Holdings from Yahoo</h3>
                <Button onClick={handleSyncAll} disabled={syncingAll}>
                    {syncingAll ? 'Syncing...' : 'Sync All Holdings from Yahoo'}
                </Button>
            </div>

            <hr style={{ border: 'none', borderTop: '1px solid #e0e0e0', marginBottom: '1.5rem' }} />

            <div style={cardStyle}>
                <h3 style={{ marginBottom: '1rem' }}>Fetch Specific Symbols</h3>
                <div style={{ display: 'flex', flexDirection: 'column', gap: '0.75rem', marginBottom: '1rem' }}>
                    <div>
                        <label style={{ display: 'block', marginBottom: '0.25rem', fontWeight: 600, fontSize: '0.85rem' }}>
                            Symbols (comma-separated)
                        </label>
                        <input
                            type="text"
                            value={symbolInput}
                            onChange={(e) => setSymbolInput(e.target.value)}
                            placeholder="FXAIX, VBTLX, BND"
                            style={{ padding: '0.5rem', border: '1px solid #ccc', borderRadius: '4px', width: '100%', maxWidth: '400px' }}
                        />
                    </div>
                    <div style={{ display: 'flex', gap: '1rem', flexWrap: 'wrap' }}>
                        <div>
                            <label style={{ display: 'block', marginBottom: '0.25rem', fontWeight: 600, fontSize: '0.85rem' }}>From</label>
                            <input type="date" value={fromDate} onChange={(e) => setFromDate(e.target.value)}
                                style={{ padding: '0.5rem', border: '1px solid #ccc', borderRadius: '4px' }} />
                        </div>
                        <div>
                            <label style={{ display: 'block', marginBottom: '0.25rem', fontWeight: 600, fontSize: '0.85rem' }}>To</label>
                            <input type="date" value={toDate} onChange={(e) => setToDate(e.target.value)}
                                style={{ padding: '0.5rem', border: '1px solid #ccc', borderRadius: '4px' }} />
                        </div>
                    </div>
                    <div>
                        <Button onClick={handleFetchPreview} disabled={fetching}>
                            {fetching ? 'Fetching...' : 'Fetch Preview'}
                        </Button>
                    </div>
                </div>

                {preview && preview.length > 0 && (
                    <>
                        <div style={{ marginBottom: '0.75rem', display: 'flex', alignItems: 'center', gap: '1rem' }}>
                            <span style={{ fontSize: '0.9rem', color: '#555' }}>{preview.length} prices fetched</span>
                            <Button onClick={handleSaveAll} disabled={saving} size="sm"
                                style={{ background: '#2e7d32', padding: '0.4rem 0.9rem', fontSize: '0.9rem' }}>
                                {saving ? 'Saving...' : 'Save All'}
                            </Button>
                        </div>
                        <div style={{ maxHeight: '320px', overflowY: 'auto' }}>
                            <table style={tableStyle}>
                                <thead>
                                    <tr>
                                        <th style={thStyle}>Symbol</th>
                                        <th style={thStyle}>Date</th>
                                        <th style={{ ...thStyle, textAlign: 'right' }}>Close Price</th>
                                    </tr>
                                </thead>
                                <tbody>
                                    {preview.map((p, i) => (
                                        <tr key={`${p.symbol}-${p.date}-${i}`} style={trHoverStyle}>
                                            <td style={{ ...tdStyle, fontWeight: 600 }}>{p.symbol}</td>
                                            <td style={{ ...tdStyle, color: '#555' }}>{p.date}</td>
                                            <td style={{ ...tdStyle, textAlign: 'right' }}>{formatCurrency(p.close_price)}</td>
                                        </tr>
                                    ))}
                                </tbody>
                            </table>
                        </div>
                    </>
                )}
                {preview && preview.length === 0 && (
                    <div style={{ color: '#999', fontSize: '0.9rem' }}>No prices returned for those symbols and date range.</div>
                )}
            </div>
        </div>
    );
}

function CsvTab() {
    const fileInputRef = useRef<HTMLInputElement>(null);
    const [uploading, setUploading] = useState(false);
    const [importedCount, setImportedCount] = useState<number | null>(null);
    const [errors, setErrors] = useState<string[]>([]);

    async function handleFileChange(e: React.ChangeEvent<HTMLInputElement>) {
        const file = e.target.files?.[0];
        if (!file) return;
        setUploading(true);
        setImportedCount(null);
        setErrors([]);
        try {
            const result = await uploadPriceCsv(file);
            setImportedCount(result.imported);
            setErrors(result.errors);
            if (result.errors.length === 0) {
                toast.success(`Imported ${result.imported} prices`);
            } else {
                toast(`Imported ${result.imported} prices with ${result.errors.length} error(s)`);
            }
        } catch {
            toast.error('CSV upload failed');
        } finally {
            setUploading(false);
            if (fileInputRef.current) fileInputRef.current.value = '';
        }
    }

    return (
        <div style={cardStyle}>
            <h3 style={{ marginBottom: '1rem' }}>Upload Price CSV</h3>
            <p style={{ marginBottom: '1rem', fontSize: '0.9rem', color: '#555' }}>
                Expected format: CSV with a header row and columns <code>symbol</code>, <code>date</code>, <code>close_price</code>.
                Date format: <code>YYYY-MM-DD</code>.
            </p>
            <div style={{ marginBottom: '1rem' }}>
                <input ref={fileInputRef} type="file" accept=".csv" onChange={handleFileChange} disabled={uploading} style={{ fontSize: '0.9rem' }} />
                {uploading && <span style={{ marginLeft: '1rem', color: '#666', fontSize: '0.9rem' }}>Uploading...</span>}
            </div>
            {importedCount !== null && (
                <div style={{ padding: '0.6rem 1rem', borderRadius: '4px', background: '#e8f5e9', color: '#2e7d32', fontSize: '0.9rem', marginBottom: errors.length > 0 ? '0.75rem' : 0, display: 'inline-block' }}>
                    Imported {importedCount} prices
                </div>
            )}
            {errors.length > 0 && (
                <div style={{ marginTop: '0.5rem' }}>
                    <div style={{ fontWeight: 600, color: '#c62828', marginBottom: '0.4rem', fontSize: '0.9rem' }}>Errors ({errors.length}):</div>
                    <ul style={{ margin: 0, paddingLeft: '1.25rem', color: '#c62828', fontSize: '0.85rem' }}>
                        {errors.map((err, i) => <li key={i}>{err}</li>)}
                    </ul>
                </div>
            )}
        </div>
    );
}
