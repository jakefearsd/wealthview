import { useState } from 'react';
import { useParams, Link } from 'react-router';
import { getAccount } from '../api/accounts';
import { listTransactions, deleteTransaction } from '../api/transactions';
import { listHoldings, updateHolding } from '../api/holdings';
import { useApiQuery } from '../hooks/useApiQuery';
import { useAuth } from '../context/AuthContext';
import { formatCurrency } from '../utils/format';
import CurrencyInput from '../components/CurrencyInput';
import { cardStyle, tableStyle, thStyle, tdStyle, trHoverStyle } from '../utils/styles';
import LoadingState from '../components/LoadingState';
import EmptyState from '../components/EmptyState';
import toast from 'react-hot-toast';
import TheoreticalPortfolioChart from '../components/TheoreticalPortfolioChart';
import TransactionForm from '../components/TransactionForm';
import Button from '../components/Button';

export default function AccountDetailPage() {
    const { id } = useParams<{ id: string }>();
    const { role } = useAuth();
    const canWrite = role === 'admin' || role === 'member';

    const { data: account, loading: acctLoading } = useApiQuery(() => getAccount(id!));
    const { data: holdings, loading: holdLoading, refetch: refetchHoldings } = useApiQuery(() => listHoldings(id!));
    const { data: txnPage, loading: txnLoading, refetch: refetchTxns } = useApiQuery(() => listTransactions(id!, 0, 50));

    const [showAdd, setShowAdd] = useState(false);
    const [editingTxnId, setEditingTxnId] = useState<string | null>(null);
    const [editingHoldingId, setEditingHoldingId] = useState<string | null>(null);
    const [editQty, setEditQty] = useState('');
    const [editCostBasis, setEditCostBasis] = useState('');

    async function handleDeleteTxn(txnId: string) {
        try {
            await deleteTransaction(txnId);
            toast.success('Transaction deleted');
            refetchTxns();
        } catch {
            toast.error('Failed to delete transaction');
        }
    }

    function startEditHolding(h: { id: string; quantity: number; cost_basis: number }) {
        setEditingHoldingId(h.id);
        setEditQty(String(h.quantity));
        setEditCostBasis(String(h.cost_basis));
    }

    async function handleSaveHolding(holdingId: string, symbol: string) {
        try {
            await updateHolding(holdingId, {
                account_id: id!,
                symbol,
                quantity: parseFloat(editQty),
                cost_basis: parseFloat(editCostBasis),
            });
            toast.success('Holding updated');
            setEditingHoldingId(null);
            refetchHoldings();
        } catch {
            toast.error('Failed to update holding');
        }
    }

    if (acctLoading || holdLoading || txnLoading) return <LoadingState message="Loading account details..." />;

    return (
        <div>
            <div style={{ marginBottom: '1.5rem' }}>
                <Link to="/accounts" style={{ color: '#1976d2', textDecoration: 'none' }}>Accounts</Link> / {account?.name}
            </div>
            <h2 style={{ marginBottom: '0.5rem' }}>{account?.name}</h2>
            <div style={{ color: '#666', marginBottom: '2rem' }}>{account?.type} {account?.institution ? `- ${account.institution}` : ''}</div>

            <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '1.5rem', marginBottom: '2rem' }}>
                <div style={cardStyle}>
                    <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '1rem' }}>
                        <h3>Holdings</h3>
                    </div>
                    <table style={tableStyle}>
                        <thead>
                            <tr>
                                <th style={thStyle}>Symbol</th>
                                <th style={{ ...thStyle, textAlign: 'right' }}>Qty</th>
                                <th style={{ ...thStyle, textAlign: 'right' }}>Cost Basis</th>
                                <th style={{ ...thStyle, textAlign: 'center' }}>Override</th>
                                {canWrite && <th style={thStyle}></th>}
                            </tr>
                        </thead>
                        <tbody>
                            {holdings?.map((h) => (
                                <tr key={h.id} style={trHoverStyle}>
                                    <td style={tdStyle}>
                                        <Link to={`/holdings/${h.id}`} style={{ color: '#1976d2', textDecoration: 'none' }}>
                                            {h.symbol}
                                        </Link>
                                        {h.is_money_market && <span style={{ color: '#999', fontSize: '0.8rem', marginLeft: '0.25rem' }}>(Money Market)</span>}
                                    </td>
                                    {editingHoldingId === h.id ? (
                                        <>
                                            <td style={{ ...tdStyle, textAlign: 'right' }}>
                                                <input type="number" value={editQty} onChange={(e) => setEditQty(e.target.value)} style={{ width: '80px', padding: '0.25rem', textAlign: 'right' }} />
                                            </td>
                                            <td style={{ ...tdStyle, textAlign: 'right' }}>
                                                <CurrencyInput value={editCostBasis} onChange={setEditCostBasis} style={{ width: '100px', padding: '0.25rem', textAlign: 'right' }} />
                                            </td>
                                            <td style={{ ...tdStyle, textAlign: 'center' }}>{h.is_manual_override ? 'Yes' : 'No'}</td>
                                            <td style={{ ...tdStyle, textAlign: 'center' }}>
                                                <button onClick={() => handleSaveHolding(h.id, h.symbol)} style={{ background: 'none', border: 'none', color: '#2e7d32', cursor: 'pointer', marginRight: '0.25rem' }}>Save</button>
                                                <button onClick={() => setEditingHoldingId(null)} style={{ background: 'none', border: 'none', color: '#666', cursor: 'pointer' }}>Cancel</button>
                                            </td>
                                        </>
                                    ) : (
                                        <>
                                            <td style={{ ...tdStyle, textAlign: 'right' }}>{h.quantity}</td>
                                            <td style={{ ...tdStyle, textAlign: 'right' }}>{formatCurrency(h.cost_basis)}</td>
                                            <td style={{ ...tdStyle, textAlign: 'center' }}>{h.is_manual_override ? 'Yes' : 'No'}</td>
                                            {canWrite && (
                                                <td style={{ ...tdStyle, textAlign: 'center' }}>
                                                    <button onClick={() => startEditHolding(h)} style={{ background: 'none', border: 'none', color: '#1976d2', cursor: 'pointer' }}>Edit</button>
                                                </td>
                                            )}
                                        </>
                                    )}
                                </tr>
                            ))}
                            {holdings?.length === 0 && <tr><td colSpan={canWrite ? 5 : 4}><EmptyState title="No holdings" /></td></tr>}
                        </tbody>
                    </table>
                </div>

                <div>
                    {canWrite && (
                        <Link to={`/accounts/${id}/import`} style={{ display: 'inline-block', padding: '0.5rem 1rem', background: '#1976d2', color: '#fff', borderRadius: '4px', textDecoration: 'none', marginBottom: '1rem' }}>
                            Import
                        </Link>
                    )}
                </div>
            </div>

            {account && account.type !== 'bank' && (
                <TheoreticalPortfolioChart accountId={id!} accountType={account.type} />
            )}

            <div style={cardStyle}>
                <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '1rem' }}>
                    <h3>Transactions</h3>
                    {canWrite && <Button onClick={() => setShowAdd(true)}>Add Transaction</Button>}
                </div>

                {showAdd && (
                    <TransactionForm
                        accountId={id!}
                        onSuccess={() => { setShowAdd(false); refetchTxns(); refetchHoldings(); }}
                        onCancel={() => setShowAdd(false)}
                    />
                )}

                <table style={tableStyle}>
                    <thead>
                        <tr>
                            <th style={thStyle}>Date</th>
                            <th style={thStyle}>Type</th>
                            <th style={thStyle}>Symbol</th>
                            <th style={{ ...thStyle, textAlign: 'right' }}>Qty</th>
                            <th style={{ ...thStyle, textAlign: 'right' }}>Amount</th>
                            {canWrite && <th style={thStyle}></th>}
                        </tr>
                    </thead>
                    <tbody>
                        {txnPage?.data.map((txn) => (
                            editingTxnId === txn.id ? (
                                <tr key={txn.id}>
                                    <td colSpan={canWrite ? 6 : 5} style={{ padding: 0 }}>
                                        <TransactionForm
                                            accountId={id!}
                                            initialValues={txn}
                                            onSuccess={() => { setEditingTxnId(null); refetchTxns(); refetchHoldings(); }}
                                            onCancel={() => setEditingTxnId(null)}
                                        />
                                    </td>
                                </tr>
                            ) : (
                                <tr key={txn.id} style={trHoverStyle}>
                                    <td style={tdStyle}>{txn.date}</td>
                                    <td style={tdStyle}>{txn.type}</td>
                                    <td style={tdStyle}>{txn.symbol || '-'}</td>
                                    <td style={{ ...tdStyle, textAlign: 'right' }}>{txn.quantity ?? '-'}</td>
                                    <td style={{ ...tdStyle, textAlign: 'right' }}>{formatCurrency(txn.amount)}</td>
                                    {canWrite && (
                                        <td style={{ ...tdStyle, textAlign: 'center' }}>
                                            <button onClick={() => setEditingTxnId(txn.id)} style={{ background: 'none', border: 'none', color: '#1976d2', cursor: 'pointer', marginRight: '0.5rem' }}>Edit</button>
                                            <button onClick={() => handleDeleteTxn(txn.id)} style={{ background: 'none', border: 'none', color: '#d32f2f', cursor: 'pointer' }}>Delete</button>
                                        </td>
                                    )}
                                </tr>
                            )
                        ))}
                        {txnPage?.data.length === 0 && <tr><td colSpan={canWrite ? 6 : 5}><EmptyState title="No transactions" /></td></tr>}
                    </tbody>
                </table>
            </div>
        </div>
    );
}
