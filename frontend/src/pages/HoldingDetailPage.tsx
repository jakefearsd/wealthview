import { useState, useEffect } from 'react';
import { useParams, Link } from 'react-router';
import { getHolding, updateHolding } from '../api/holdings';
import { listTransactions } from '../api/transactions';
import { useApiQuery } from '../hooks/useApiQuery';
import { useAuth } from '../context/AuthContext';
import { formatCurrency } from '../utils/format';
import CurrencyInput from '../components/CurrencyInput';
import { cardStyle, tableStyle, thStyle, tdStyle, trHoverStyle } from '../utils/styles';
import LoadingState from '../components/LoadingState';
import EmptyState from '../components/EmptyState';
import Button from '../components/Button';
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

    if (holdingLoading) return <LoadingState message="Loading holding..." />;
    if (!holding) return <EmptyState title="Holding not found" />;

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
                        <Button onClick={startEdit}>
                            Edit Override
                        </Button>
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
                            <Button onClick={handleSave} style={{ background: '#2e7d32', marginRight: '0.5rem' }}>Save</Button>
                            <Button onClick={() => setEditing(false)} style={{ background: '#666' }}>Cancel</Button>
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
                    <LoadingState message="Loading transactions..." />
                ) : (
                    <table style={tableStyle}>
                        <thead>
                            <tr>
                                <th style={thStyle}>Date</th>
                                <th style={thStyle}>Type</th>
                                <th style={{ ...thStyle, textAlign: 'right' }}>Qty</th>
                                <th style={{ ...thStyle, textAlign: 'right' }}>Amount</th>
                            </tr>
                        </thead>
                        <tbody>
                            {transactions.map((txn) => (
                                <tr key={txn.id} style={trHoverStyle}>
                                    <td style={tdStyle}>{txn.date}</td>
                                    <td style={tdStyle}>{txn.type}</td>
                                    <td style={{ ...tdStyle, textAlign: 'right' }}>{txn.quantity ?? '-'}</td>
                                    <td style={{ ...tdStyle, textAlign: 'right' }}>{formatCurrency(txn.amount)}</td>
                                </tr>
                            ))}
                            {transactions.length === 0 && (
                                <tr><td colSpan={4}><EmptyState title={`No transactions for ${holding.symbol}`} /></td></tr>
                            )}
                        </tbody>
                    </table>
                )}
            </div>
        </div>
    );
}
