import { useState, useEffect } from 'react';
import { useParams, Link } from 'react-router';
import { getHolding, updateHolding } from '../api/holdings';
import { listTransactions } from '../api/transactions';
import { useApiQuery } from '../hooks/useApiQuery';
import { useAuth } from '../context/AuthContext';
import { formatCurrency } from '../utils/format';
import CurrencyInput from '../components/CurrencyInput';
import { cardStyle } from '../utils/styles';
import type { Transaction } from '../types/transaction';
import toast from 'react-hot-toast';

export default function HoldingDetailPage() {
    const { id } = useParams<{ id: string }>();
    const { role } = useAuth();
    const canWrite = role === 'admin' || role === 'member';

    const { data: holding, loading: holdingLoading, refetch: refetchHolding } = useApiQuery(() => getHolding(id!));

    const [transactions, setTransactions] = useState<Transaction[]>([]);
    const [txnLoading, setTxnLoading] = useState(true);

    useEffect(() => {
        if (!holding) return;
        setTxnLoading(true);
        listTransactions(holding.account_id, 0, 100, holding.symbol)
            .then((page) => setTransactions(page.data))
            .catch(() => setTransactions([]))
            .finally(() => setTxnLoading(false));
    }, [holding?.account_id, holding?.symbol]);

    const [editing, setEditing] = useState(false);
    const [editQty, setEditQty] = useState('');
    const [editCostBasis, setEditCostBasis] = useState('');

    function startEdit() {
        if (!holding) return;
        setEditQty(String(holding.quantity));
        setEditCostBasis(String(holding.cost_basis));
        setEditing(true);
    }

    async function handleSave() {
        if (!holding) return;
        try {
            await updateHolding(holding.id, {
                account_id: holding.account_id,
                symbol: holding.symbol,
                quantity: parseFloat(editQty),
                cost_basis: parseFloat(editCostBasis),
            });
            toast.success('Holding updated');
            setEditing(false);
            refetchHolding();
        } catch {
            toast.error('Failed to update holding');
        }
    }

    if (holdingLoading) return <div>Loading...</div>;
    if (!holding) return <div>Holding not found</div>;

    return (
        <div>
            <div style={{ marginBottom: '1.5rem' }}>
                <Link to={`/accounts/${holding.account_id}`} style={{ color: '#1976d2', textDecoration: 'none' }}>Account</Link> / {holding.symbol}
            </div>

            <h2 style={{ marginBottom: '1.5rem' }}>{holding.symbol}</h2>

            <div style={{ ...cardStyle, marginBottom: '2rem' }}>
                <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '1rem' }}>
                    <h3>Holding Summary</h3>
                    {canWrite && !editing && (
                        <button onClick={startEdit} style={{ padding: '0.4rem 0.8rem', background: '#1976d2', color: '#fff', border: 'none', borderRadius: '4px', cursor: 'pointer' }}>
                            Edit Override
                        </button>
                    )}
                </div>

                {editing ? (
                    <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '1rem', maxWidth: '400px' }}>
                        <div>
                            <label style={{ display: 'block', marginBottom: '0.25rem', fontWeight: 'bold' }}>Quantity</label>
                            <input type="number" step="any" value={editQty} onChange={(e) => setEditQty(e.target.value)}
                                style={{ width: '100%', padding: '0.5rem', border: '1px solid #ccc', borderRadius: '4px' }} />
                        </div>
                        <div>
                            <label style={{ display: 'block', marginBottom: '0.25rem', fontWeight: 'bold' }}>Cost Basis</label>
                            <CurrencyInput value={editCostBasis} onChange={setEditCostBasis}
                                style={{ width: '100%', padding: '0.5rem', border: '1px solid #ccc', borderRadius: '4px' }} />
                        </div>
                        <div style={{ gridColumn: '1 / -1' }}>
                            <button onClick={handleSave} style={{ padding: '0.4rem 0.8rem', background: '#2e7d32', color: '#fff', border: 'none', borderRadius: '4px', cursor: 'pointer', marginRight: '0.5rem' }}>Save</button>
                            <button onClick={() => setEditing(false)} style={{ padding: '0.4rem 0.8rem', background: '#666', color: '#fff', border: 'none', borderRadius: '4px', cursor: 'pointer' }}>Cancel</button>
                        </div>
                    </div>
                ) : (
                    <table style={{ borderCollapse: 'collapse' }}>
                        <tbody>
                            <tr><td style={{ padding: '0.5rem 1rem 0.5rem 0', fontWeight: 'bold' }}>Symbol</td><td style={{ padding: '0.5rem 0' }}>{holding.symbol}</td></tr>
                            <tr><td style={{ padding: '0.5rem 1rem 0.5rem 0', fontWeight: 'bold' }}>Quantity</td><td style={{ padding: '0.5rem 0' }}>{holding.quantity}</td></tr>
                            <tr><td style={{ padding: '0.5rem 1rem 0.5rem 0', fontWeight: 'bold' }}>Cost Basis</td><td style={{ padding: '0.5rem 0' }}>{formatCurrency(holding.cost_basis)}</td></tr>
                            <tr><td style={{ padding: '0.5rem 1rem 0.5rem 0', fontWeight: 'bold' }}>Manual Override</td><td style={{ padding: '0.5rem 0' }}>{holding.is_manual_override ? 'Yes' : 'No'}</td></tr>
                            {holding.is_money_market && (
                                <tr><td style={{ padding: '0.5rem 1rem 0.5rem 0', fontWeight: 'bold' }}>Money Market Rate</td><td style={{ padding: '0.5rem 0' }}>{holding.money_market_rate != null ? `${holding.money_market_rate}%` : '-'}</td></tr>
                            )}
                            <tr><td style={{ padding: '0.5rem 1rem 0.5rem 0', fontWeight: 'bold' }}>As Of Date</td><td style={{ padding: '0.5rem 0' }}>{holding.as_of_date}</td></tr>
                        </tbody>
                    </table>
                )}
            </div>

            <div style={cardStyle}>
                <h3 style={{ marginBottom: '1rem' }}>Transactions for {holding.symbol}</h3>
                {txnLoading ? (
                    <div>Loading transactions...</div>
                ) : (
                    <table style={{ width: '100%', borderCollapse: 'collapse' }}>
                        <thead>
                            <tr style={{ borderBottom: '2px solid #e0e0e0' }}>
                                <th style={{ textAlign: 'left', padding: '0.5rem' }}>Date</th>
                                <th style={{ textAlign: 'left', padding: '0.5rem' }}>Type</th>
                                <th style={{ textAlign: 'right', padding: '0.5rem' }}>Qty</th>
                                <th style={{ textAlign: 'right', padding: '0.5rem' }}>Amount</th>
                            </tr>
                        </thead>
                        <tbody>
                            {transactions.map((txn) => (
                                <tr key={txn.id} style={{ borderBottom: '1px solid #f0f0f0' }}>
                                    <td style={{ padding: '0.5rem' }}>{txn.date}</td>
                                    <td style={{ padding: '0.5rem' }}>{txn.type}</td>
                                    <td style={{ padding: '0.5rem', textAlign: 'right' }}>{txn.quantity ?? '-'}</td>
                                    <td style={{ padding: '0.5rem', textAlign: 'right' }}>{formatCurrency(txn.amount)}</td>
                                </tr>
                            ))}
                            {transactions.length === 0 && (
                                <tr><td colSpan={4} style={{ padding: '1rem', color: '#999', textAlign: 'center' }}>No transactions for {holding.symbol}</td></tr>
                            )}
                        </tbody>
                    </table>
                )}
            </div>
        </div>
    );
}
