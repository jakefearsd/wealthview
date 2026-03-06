import { useState } from 'react';
import { useParams, Link } from 'react-router-dom';
import { getAccount } from '../api/accounts';
import { listTransactions, deleteTransaction } from '../api/transactions';
import { listHoldings } from '../api/holdings';
import { useApiQuery } from '../hooks/useApiQuery';
import { useAuth } from '../context/AuthContext';
import { formatCurrency } from '../utils/format';
import { cardStyle } from '../utils/styles';
import toast from 'react-hot-toast';
import TheoreticalPortfolioChart from '../components/TheoreticalPortfolioChart';
import TransactionForm from '../components/TransactionForm';

export default function AccountDetailPage() {
    const { id } = useParams<{ id: string }>();
    const { role } = useAuth();
    const canWrite = role === 'admin' || role === 'member';

    const { data: account, loading: acctLoading } = useApiQuery(() => getAccount(id!));
    const { data: holdings, loading: holdLoading } = useApiQuery(() => listHoldings(id!));
    const { data: txnPage, loading: txnLoading, refetch: refetchTxns } = useApiQuery(() => listTransactions(id!, 0, 50));

    const [showAdd, setShowAdd] = useState(false);

    async function handleDeleteTxn(txnId: string) {
        try {
            await deleteTransaction(txnId);
            toast.success('Transaction deleted');
            refetchTxns();
        } catch {
            toast.error('Failed to delete transaction');
        }
    }

    if (acctLoading || holdLoading || txnLoading) return <div>Loading...</div>;

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
                    <table style={{ width: '100%', borderCollapse: 'collapse' }}>
                        <thead>
                            <tr style={{ borderBottom: '2px solid #e0e0e0' }}>
                                <th style={{ textAlign: 'left', padding: '0.5rem' }}>Symbol</th>
                                <th style={{ textAlign: 'right', padding: '0.5rem' }}>Qty</th>
                                <th style={{ textAlign: 'right', padding: '0.5rem' }}>Cost Basis</th>
                                <th style={{ textAlign: 'center', padding: '0.5rem' }}>Override</th>
                            </tr>
                        </thead>
                        <tbody>
                            {holdings?.map((h) => (
                                <tr key={h.id} style={{ borderBottom: '1px solid #f0f0f0' }}>
                                    <td style={{ padding: '0.5rem' }}>{h.symbol}</td>
                                    <td style={{ padding: '0.5rem', textAlign: 'right' }}>{h.quantity}</td>
                                    <td style={{ padding: '0.5rem', textAlign: 'right' }}>{formatCurrency(h.cost_basis)}</td>
                                    <td style={{ padding: '0.5rem', textAlign: 'center' }}>{h.is_manual_override ? 'Yes' : 'No'}</td>
                                </tr>
                            ))}
                            {holdings?.length === 0 && <tr><td colSpan={4} style={{ padding: '1rem', color: '#999', textAlign: 'center' }}>No holdings</td></tr>}
                        </tbody>
                    </table>
                </div>

                <div>
                    {canWrite && (
                        <Link to={`/accounts/${id}/import`} style={{ display: 'inline-block', padding: '0.5rem 1rem', background: '#1976d2', color: '#fff', borderRadius: '4px', textDecoration: 'none', marginBottom: '1rem' }}>
                            Import Transactions
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
                    {canWrite && <button onClick={() => setShowAdd(true)} style={{ padding: '0.4rem 0.8rem', background: '#1976d2', color: '#fff', border: 'none', borderRadius: '4px', cursor: 'pointer' }}>Add Transaction</button>}
                </div>

                {showAdd && (
                    <TransactionForm
                        accountId={id!}
                        onSuccess={() => { setShowAdd(false); refetchTxns(); }}
                        onCancel={() => setShowAdd(false)}
                    />
                )}

                <table style={{ width: '100%', borderCollapse: 'collapse' }}>
                    <thead>
                        <tr style={{ borderBottom: '2px solid #e0e0e0' }}>
                            <th style={{ textAlign: 'left', padding: '0.5rem' }}>Date</th>
                            <th style={{ textAlign: 'left', padding: '0.5rem' }}>Type</th>
                            <th style={{ textAlign: 'left', padding: '0.5rem' }}>Symbol</th>
                            <th style={{ textAlign: 'right', padding: '0.5rem' }}>Qty</th>
                            <th style={{ textAlign: 'right', padding: '0.5rem' }}>Amount</th>
                            {canWrite && <th style={{ padding: '0.5rem' }}></th>}
                        </tr>
                    </thead>
                    <tbody>
                        {txnPage?.data.map((txn) => (
                            <tr key={txn.id} style={{ borderBottom: '1px solid #f0f0f0' }}>
                                <td style={{ padding: '0.5rem' }}>{txn.date}</td>
                                <td style={{ padding: '0.5rem' }}>{txn.type}</td>
                                <td style={{ padding: '0.5rem' }}>{txn.symbol || '-'}</td>
                                <td style={{ padding: '0.5rem', textAlign: 'right' }}>{txn.quantity ?? '-'}</td>
                                <td style={{ padding: '0.5rem', textAlign: 'right' }}>{formatCurrency(txn.amount)}</td>
                                {canWrite && (
                                    <td style={{ padding: '0.5rem', textAlign: 'center' }}>
                                        <button onClick={() => handleDeleteTxn(txn.id)} style={{ background: 'none', border: 'none', color: '#d32f2f', cursor: 'pointer' }}>Delete</button>
                                    </td>
                                )}
                            </tr>
                        ))}
                        {txnPage?.data.length === 0 && <tr><td colSpan={canWrite ? 6 : 5} style={{ padding: '1rem', color: '#999', textAlign: 'center' }}>No transactions</td></tr>}
                    </tbody>
                </table>
            </div>
        </div>
    );
}
