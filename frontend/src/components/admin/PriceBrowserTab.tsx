import { useState } from 'react';
import { LineChart, Line, XAxis, YAxis, Tooltip, ResponsiveContainer, CartesianGrid } from 'recharts';
import client from '../../api/client';
import { cardStyle } from '../../utils/styles';
import { formatCurrency } from '../../utils/format';
import toast from 'react-hot-toast';

interface PriceRecord {
    symbol: string;
    date: string;
    close_price: number;
    source: string;
}

function todayStr(): string {
    return new Date().toISOString().slice(0, 10);
}

function thirtyDaysAgoStr(): string {
    const d = new Date();
    d.setDate(d.getDate() - 30);
    return d.toISOString().slice(0, 10);
}

export default function PriceBrowserTab() {
    const [symbol, setSymbol] = useState('');
    const [fromDate, setFromDate] = useState(thirtyDaysAgoStr());
    const [toDate, setToDate] = useState(todayStr());
    const [prices, setPrices] = useState<PriceRecord[]>([]);
    const [loading, setLoading] = useState(false);

    async function handleSearch() {
        const sym = symbol.trim().toUpperCase();
        if (!sym) {
            toast.error('Enter a symbol');
            return;
        }
        setLoading(true);
        try {
            const { data } = await client.get<PriceRecord[]>(`/admin/prices/${sym}/history`, {
                params: { from: fromDate, to: toDate },
            });
            setPrices(data);
            if (data.length === 0) toast('No price data found for that range');
        } catch {
            toast.error('Failed to fetch prices');
        } finally {
            setLoading(false);
        }
    }

    async function handleDelete(date: string) {
        const sym = symbol.trim().toUpperCase();
        if (!confirm(`Delete price for ${sym} on ${date}?`)) return;
        try {
            await client.delete(`/admin/prices/${sym}/${date}`);
            toast.success('Price deleted');
            setPrices((prev) => prev.filter((p) => p.date !== date));
        } catch {
            toast.error('Failed to delete price');
        }
    }

    const chartData = prices.map((p) => ({ date: p.date, price: p.close_price }));

    return (
        <div>
            <div style={{ ...cardStyle, marginBottom: '1.5rem' }}>
                <div style={{ display: 'flex', gap: '0.75rem', alignItems: 'flex-end', flexWrap: 'wrap' }}>
                    <div>
                        <label style={{ display: 'block', marginBottom: '0.25rem', fontWeight: 600, fontSize: '0.85rem' }}>
                            Symbol
                        </label>
                        <input
                            type="text"
                            value={symbol}
                            onChange={(e) => setSymbol(e.target.value)}
                            placeholder="VOO"
                            style={{ padding: '0.5rem', border: '1px solid #ccc', borderRadius: '4px', width: '120px' }}
                            onKeyDown={(e) => e.key === 'Enter' && handleSearch()}
                        />
                    </div>
                    <div>
                        <label style={{ display: 'block', marginBottom: '0.25rem', fontWeight: 600, fontSize: '0.85rem' }}>
                            From
                        </label>
                        <input
                            type="date"
                            value={fromDate}
                            onChange={(e) => setFromDate(e.target.value)}
                            style={{ padding: '0.5rem', border: '1px solid #ccc', borderRadius: '4px' }}
                        />
                    </div>
                    <div>
                        <label style={{ display: 'block', marginBottom: '0.25rem', fontWeight: 600, fontSize: '0.85rem' }}>
                            To
                        </label>
                        <input
                            type="date"
                            value={toDate}
                            onChange={(e) => setToDate(e.target.value)}
                            style={{ padding: '0.5rem', border: '1px solid #ccc', borderRadius: '4px' }}
                        />
                    </div>
                    <button
                        onClick={handleSearch}
                        disabled={loading}
                        style={{
                            padding: '0.5rem 1rem',
                            background: '#1976d2',
                            color: '#fff',
                            border: 'none',
                            borderRadius: '4px',
                            cursor: loading ? 'not-allowed' : 'pointer',
                        }}
                    >
                        {loading ? 'Loading...' : 'Search'}
                    </button>
                </div>
            </div>

            {chartData.length > 1 && (
                <div style={{ ...cardStyle, marginBottom: '1.5rem' }}>
                    <h3 style={{ marginBottom: '1rem' }}>
                        {symbol.trim().toUpperCase()} Price History
                    </h3>
                    <ResponsiveContainer width="100%" height={250}>
                        <LineChart data={chartData}>
                            <CartesianGrid strokeDasharray="3 3" />
                            <XAxis dataKey="date" fontSize={12} />
                            <YAxis domain={['auto', 'auto']} fontSize={12} tickFormatter={(v) => `$${v}`} />
                            <Tooltip formatter={(v: number) => formatCurrency(v)} />
                            <Line type="monotone" dataKey="price" stroke="#1976d2" dot={false} strokeWidth={2} />
                        </LineChart>
                    </ResponsiveContainer>
                </div>
            )}

            {prices.length > 0 && (
                <div style={cardStyle}>
                    <div style={{ marginBottom: '0.75rem', fontSize: '0.9rem', color: '#555' }}>
                        {prices.length} prices found
                    </div>
                    <div style={{ maxHeight: '400px', overflowY: 'auto' }}>
                        <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: '0.9rem' }}>
                            <thead>
                                <tr style={{ borderBottom: '2px solid #e0e0e0' }}>
                                    <th style={{ textAlign: 'left', padding: '0.4rem 0.5rem' }}>Date</th>
                                    <th style={{ textAlign: 'right', padding: '0.4rem 0.5rem' }}>Close Price</th>
                                    <th style={{ textAlign: 'left', padding: '0.4rem 0.5rem' }}>Source</th>
                                    <th style={{ textAlign: 'center', padding: '0.4rem 0.5rem' }}></th>
                                </tr>
                            </thead>
                            <tbody>
                                {prices.map((p) => (
                                    <tr key={p.date} style={{ borderBottom: '1px solid #f0f0f0' }}>
                                        <td style={{ padding: '0.4rem 0.5rem' }}>{p.date}</td>
                                        <td style={{ padding: '0.4rem 0.5rem', textAlign: 'right' }}>
                                            {formatCurrency(p.close_price)}
                                        </td>
                                        <td style={{ padding: '0.4rem 0.5rem', color: '#666' }}>
                                            {p.source ?? '-'}
                                        </td>
                                        <td style={{ padding: '0.4rem 0.5rem', textAlign: 'center' }}>
                                            <button
                                                onClick={() => handleDelete(p.date)}
                                                style={{ background: 'none', border: 'none', color: '#d32f2f', cursor: 'pointer', fontSize: '0.85rem' }}
                                            >
                                                Delete
                                            </button>
                                        </td>
                                    </tr>
                                ))}
                            </tbody>
                        </table>
                    </div>
                </div>
            )}
        </div>
    );
}
