import { useState, useEffect } from 'react';
import { listExchangeRates, createExchangeRate, updateExchangeRate, deleteExchangeRate } from '../../api/exchangeRates';
import type { ExchangeRate } from '../../types/exchangeRate';
import { cardStyle } from '../../utils/styles';
import toast from 'react-hot-toast';

export default function ExchangeRatesSection() {
    const [rates, setRates] = useState<ExchangeRate[]>([]);
    const [loading, setLoading] = useState(true);
    const [editingCode, setEditingCode] = useState<string | null>(null);
    const [editRate, setEditRate] = useState('');
    const [showAdd, setShowAdd] = useState(false);
    const [newCode, setNewCode] = useState('');
    const [newRate, setNewRate] = useState('');
    const [saving, setSaving] = useState(false);

    useEffect(() => {
        loadRates();
    }, []);

    async function loadRates() {
        setLoading(true);
        try {
            setRates(await listExchangeRates());
        } catch {
            toast.error('Failed to load exchange rates');
        } finally {
            setLoading(false);
        }
    }

    async function handleAdd() {
        if (!newCode || !newRate) return;
        setSaving(true);
        try {
            await createExchangeRate({
                currency_code: newCode.toUpperCase(),
                rate_to_usd: parseFloat(newRate),
            });
            toast.success(`${newCode.toUpperCase()} rate added`);
            setShowAdd(false);
            setNewCode('');
            setNewRate('');
            loadRates();
        } catch {
            toast.error('Failed to add exchange rate');
        } finally {
            setSaving(false);
        }
    }

    async function handleUpdate(currencyCode: string) {
        if (!editRate) return;
        setSaving(true);
        try {
            await updateExchangeRate(currencyCode, {
                currency_code: currencyCode,
                rate_to_usd: parseFloat(editRate),
            });
            toast.success(`${currencyCode} rate updated`);
            setEditingCode(null);
            loadRates();
        } catch {
            toast.error('Failed to update exchange rate');
        } finally {
            setSaving(false);
        }
    }

    async function handleDelete(currencyCode: string) {
        if (!confirm(`Delete ${currencyCode} exchange rate?`)) return;
        try {
            await deleteExchangeRate(currencyCode);
            toast.success(`${currencyCode} rate deleted`);
            loadRates();
        } catch {
            toast.error('Failed to delete — accounts may still use this currency');
        }
    }

    if (loading) return <div>Loading exchange rates...</div>;

    return (
        <div>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '1.5rem' }}>
                <h2>Exchange Rates</h2>
                <button
                    onClick={() => setShowAdd(true)}
                    style={{ padding: '0.5rem 1rem', background: '#1976d2', color: '#fff', border: 'none', borderRadius: '4px', cursor: 'pointer' }}
                >
                    Add Currency
                </button>
            </div>

            {showAdd && (
                <div style={{ ...cardStyle, marginBottom: '1.5rem' }}>
                    <h3 style={{ marginBottom: '1rem' }}>Add Exchange Rate</h3>
                    <div style={{ display: 'flex', gap: '1rem', alignItems: 'center' }}>
                        <div>
                            <div style={{ fontSize: '0.75rem', color: '#999', marginBottom: '0.25rem' }}>Currency Code</div>
                            <input
                                placeholder="EUR"
                                value={newCode}
                                onChange={(e) => setNewCode(e.target.value.toUpperCase())}
                                maxLength={3}
                                style={{ padding: '0.5rem', border: '1px solid #ccc', borderRadius: '4px', width: '80px' }}
                            />
                        </div>
                        <div>
                            <div style={{ fontSize: '0.75rem', color: '#999', marginBottom: '0.25rem' }}>1 {newCode || '???'} = ? USD</div>
                            <input
                                placeholder="1.08"
                                value={newRate}
                                onChange={(e) => setNewRate(e.target.value)}
                                type="number"
                                step="0.0001"
                                style={{ padding: '0.5rem', border: '1px solid #ccc', borderRadius: '4px', width: '120px' }}
                            />
                        </div>
                        <div style={{ display: 'flex', gap: '0.5rem', alignSelf: 'flex-end' }}>
                            <button onClick={handleAdd} disabled={saving}
                                    style={{ padding: '0.5rem 1rem', background: '#2e7d32', color: '#fff', border: 'none', borderRadius: '4px', cursor: 'pointer' }}>
                                {saving ? '...' : 'Save'}
                            </button>
                            <button onClick={() => { setShowAdd(false); setNewCode(''); setNewRate(''); }}
                                    style={{ padding: '0.5rem 1rem', background: '#eee', border: 'none', borderRadius: '4px', cursor: 'pointer' }}>
                                Cancel
                            </button>
                        </div>
                    </div>
                </div>
            )}

            <div style={cardStyle}>
                {rates.length === 0 ? (
                    <div style={{ color: '#999' }}>No exchange rates configured. All accounts use USD.</div>
                ) : (
                    <table style={{ width: '100%', borderCollapse: 'collapse' }}>
                        <thead>
                            <tr style={{ borderBottom: '2px solid #eee' }}>
                                <th style={{ textAlign: 'left', padding: '0.5rem', fontSize: '0.85rem', color: '#999' }}>Currency</th>
                                <th style={{ textAlign: 'left', padding: '0.5rem', fontSize: '0.85rem', color: '#999' }}>Rate to USD</th>
                                <th style={{ textAlign: 'left', padding: '0.5rem', fontSize: '0.85rem', color: '#999' }}>Last Updated</th>
                                <th style={{ padding: '0.5rem' }}></th>
                            </tr>
                        </thead>
                        <tbody>
                            {rates.map((rate) => (
                                <tr key={rate.currency_code} style={{ borderBottom: '1px solid #f0f0f0' }}>
                                    <td style={{ padding: '0.5rem', fontWeight: 600 }}>{rate.currency_code}</td>
                                    <td style={{ padding: '0.5rem' }}>
                                        {editingCode === rate.currency_code ? (
                                            <div style={{ display: 'flex', gap: '0.5rem', alignItems: 'center' }}>
                                                <input
                                                    value={editRate}
                                                    onChange={(e) => setEditRate(e.target.value)}
                                                    type="number"
                                                    step="0.0001"
                                                    style={{ padding: '0.4rem', border: '1px solid #ccc', borderRadius: '4px', width: '120px' }}
                                                    autoFocus
                                                />
                                                <button onClick={() => handleUpdate(rate.currency_code)} disabled={saving}
                                                        style={{ padding: '0.4rem 0.8rem', background: '#1976d2', color: '#fff', border: 'none', borderRadius: '4px', cursor: 'pointer', fontSize: '0.85rem' }}>
                                                    {saving ? '...' : 'Save'}
                                                </button>
                                                <button onClick={() => setEditingCode(null)}
                                                        style={{ padding: '0.4rem 0.8rem', background: '#fff', border: '1px solid #ccc', borderRadius: '4px', cursor: 'pointer', fontSize: '0.85rem' }}>
                                                    Cancel
                                                </button>
                                            </div>
                                        ) : (
                                            <span style={{ fontFamily: 'monospace' }}>
                                                1 {rate.currency_code} = {rate.rate_to_usd} USD
                                            </span>
                                        )}
                                    </td>
                                    <td style={{ padding: '0.5rem', color: '#999', fontSize: '0.85rem' }}>
                                        {new Date(rate.updated_at).toLocaleDateString()}
                                    </td>
                                    <td style={{ padding: '0.5rem', textAlign: 'right' }}>
                                        {editingCode !== rate.currency_code && (
                                            <div style={{ display: 'flex', gap: '0.5rem', justifyContent: 'flex-end' }}>
                                                <button onClick={() => { setEditingCode(rate.currency_code); setEditRate(String(rate.rate_to_usd)); }}
                                                        style={{ padding: '0.3rem 0.6rem', background: '#1976d2', color: '#fff', border: 'none', borderRadius: '4px', cursor: 'pointer', fontSize: '0.8rem' }}>
                                                    Edit
                                                </button>
                                                <button onClick={() => handleDelete(rate.currency_code)}
                                                        style={{ padding: '0.3rem 0.6rem', background: '#d32f2f', color: '#fff', border: 'none', borderRadius: '4px', cursor: 'pointer', fontSize: '0.8rem' }}>
                                                    Delete
                                                </button>
                                            </div>
                                        )}
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
