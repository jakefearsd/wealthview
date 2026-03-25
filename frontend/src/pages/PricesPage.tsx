import { useEffect, useState } from 'react';
import { createPrice, listLatestPrices } from '../api/prices';
import { useAuth } from '../context/AuthContext';
import { formatCurrency } from '../utils/format';
import CurrencyInput from '../components/CurrencyInput';
import toast from 'react-hot-toast';
import type { Price } from '../types/price';

export default function PricesPage() {
    const { role } = useAuth();
    const canWrite = role === 'admin' || role === 'member';

    const [symbol, setSymbol] = useState('');
    const [date, setDate] = useState('');
    const [price, setPrice] = useState('');
    const [recentPrices, setRecentPrices] = useState<Array<{ symbol: string; date: string; close_price: number }>>([]);
    const [latestPrices, setLatestPrices] = useState<Price[]>([]);
    const [loading, setLoading] = useState(true);

    async function fetchLatestPrices() {
        setLoading(true);
        try {
            const data = await listLatestPrices();
            setLatestPrices(data);
        } catch (err) {
            console.error('Failed to load latest prices', err);
            toast.error('Failed to load prices');
        } finally {
            setLoading(false);
        }
    }

    useEffect(() => {
        fetchLatestPrices();
    }, []);

    async function handleAddPrice() {
        try {
            const result = await createPrice({
                symbol: symbol.toUpperCase(),
                date,
                close_price: parseFloat(price),
            });
            toast.success(`Price for ${result.symbol} saved`);
            setRecentPrices([result, ...recentPrices]);
            setSymbol('');
            setDate('');
            setPrice('');
            await fetchLatestPrices();
        } catch (err) {
            console.error('Failed to save price', err);
            toast.error('Failed to save price');
        }
    }

    return (
        <div>
            <h2 style={{ marginBottom: '1.5rem' }}>Prices</h2>

            {canWrite && (
                <div style={{ background: '#fff', padding: '1.5rem', borderRadius: '8px', marginBottom: '2rem', boxShadow: '0 1px 3px rgba(0,0,0,0.1)' }}>
                    <h3 style={{ marginBottom: '1rem' }}>Add Manual Price</h3>
                    <div style={{ display: 'flex', gap: '1rem', alignItems: 'end' }}>
                        <div>
                            <label style={{ display: 'block', fontSize: '0.85rem', marginBottom: '0.25rem' }}>Symbol</label>
                            <input value={symbol} onChange={(e) => setSymbol(e.target.value)} placeholder="AAPL" style={{ padding: '0.5rem', border: '1px solid #ccc', borderRadius: '4px' }} />
                        </div>
                        <div>
                            <label style={{ display: 'block', fontSize: '0.85rem', marginBottom: '0.25rem' }}>Date</label>
                            <input type="date" value={date} onChange={(e) => setDate(e.target.value)} style={{ padding: '0.5rem', border: '1px solid #ccc', borderRadius: '4px' }} />
                        </div>
                        <div>
                            <label style={{ display: 'block', fontSize: '0.85rem', marginBottom: '0.25rem' }}>Price</label>
                            <CurrencyInput value={price} onChange={setPrice} placeholder="185.50" style={{ padding: '0.5rem', border: '1px solid #ccc', borderRadius: '4px' }} />
                        </div>
                        <button onClick={handleAddPrice} style={{ padding: '0.5rem 1rem', background: '#1976d2', color: '#fff', border: 'none', borderRadius: '4px', cursor: 'pointer' }}>Save</button>
                    </div>
                </div>
            )}

            <div style={{ background: '#fff', padding: '1.5rem', borderRadius: '8px', marginBottom: '2rem', boxShadow: '0 1px 3px rgba(0,0,0,0.1)' }}>
                <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '1rem' }}>
                    <h3 style={{ margin: 0 }}>Latest Prices</h3>
                    <button
                        onClick={fetchLatestPrices}
                        disabled={loading}
                        style={{ padding: '0.4rem 0.9rem', background: '#f5f5f5', border: '1px solid #ccc', borderRadius: '4px', cursor: loading ? 'not-allowed' : 'pointer', fontSize: '0.85rem' }}
                    >
                        {loading ? 'Loading…' : 'Refresh'}
                    </button>
                </div>
                {loading ? (
                    <p style={{ color: '#888', margin: 0 }}>Loading…</p>
                ) : latestPrices.length === 0 ? (
                    <p style={{ color: '#888', margin: 0 }}>No prices on record.</p>
                ) : (
                    <table style={{ width: '100%', borderCollapse: 'collapse' }}>
                        <thead>
                            <tr style={{ borderBottom: '2px solid #e0e0e0' }}>
                                <th style={{ textAlign: 'left', padding: '0.5rem' }}>Symbol</th>
                                <th style={{ textAlign: 'left', padding: '0.5rem' }}>Latest Date</th>
                                <th style={{ textAlign: 'right', padding: '0.5rem' }}>Close Price</th>
                                <th style={{ textAlign: 'left', padding: '0.5rem' }}>Source</th>
                            </tr>
                        </thead>
                        <tbody>
                            {latestPrices.map((p) => (
                                <tr key={p.symbol} style={{ borderBottom: '1px solid #f0f0f0' }}>
                                    <td style={{ padding: '0.5rem', fontWeight: 500 }}>{p.symbol}</td>
                                    <td style={{ padding: '0.5rem' }}>{p.date}</td>
                                    <td style={{ padding: '0.5rem', textAlign: 'right' }}>{formatCurrency(p.close_price)}</td>
                                    <td style={{ padding: '0.5rem', color: '#666', fontSize: '0.85rem' }}>{p.source}</td>
                                </tr>
                            ))}
                        </tbody>
                    </table>
                )}
            </div>

            {recentPrices.length > 0 && (
                <div style={{ background: '#fff', padding: '1.5rem', borderRadius: '8px', boxShadow: '0 1px 3px rgba(0,0,0,0.1)' }}>
                    <h3 style={{ marginBottom: '1rem' }}>Recently Added</h3>
                    <table style={{ width: '100%', borderCollapse: 'collapse' }}>
                        <thead>
                            <tr style={{ borderBottom: '2px solid #e0e0e0' }}>
                                <th style={{ textAlign: 'left', padding: '0.5rem' }}>Symbol</th>
                                <th style={{ textAlign: 'left', padding: '0.5rem' }}>Date</th>
                                <th style={{ textAlign: 'right', padding: '0.5rem' }}>Price</th>
                            </tr>
                        </thead>
                        <tbody>
                            {recentPrices.map((p, i) => (
                                <tr key={i} style={{ borderBottom: '1px solid #f0f0f0' }}>
                                    <td style={{ padding: '0.5rem' }}>{p.symbol}</td>
                                    <td style={{ padding: '0.5rem' }}>{p.date}</td>
                                    <td style={{ padding: '0.5rem', textAlign: 'right' }}>{formatCurrency(p.close_price)}</td>
                                </tr>
                            ))}
                        </tbody>
                    </table>
                </div>
            )}
        </div>
    );
}
