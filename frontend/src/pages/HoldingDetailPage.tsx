import { useState } from 'react';
import { useParams, Link } from 'react-router';
import { getHolding, updateHolding } from '../api/holdings';
import { getAccount } from '../api/accounts';
import { listTransactions } from '../api/transactions';
import { useApiQuery } from '../hooks/useApiQuery';
import { useApiMutation } from '../hooks/useApiMutation';
import { useAuth } from '../context/AuthContext';
import { hasWriteAccess } from '../utils/permissions';
import { formatCurrency, formatDate } from '../utils/format';
import { capitalize } from '../utils/accountTypes';
import CurrencyInput from '../components/CurrencyInput';
import { cardStyle, tableStyle, thStyle, tdStyle, trHoverStyle } from '../utils/styles';
import LoadingState from '../components/LoadingState';
import EmptyState from '../components/EmptyState';
import ErrorState from '../components/ErrorState';
import Button from '../components/Button';
import RecentStockSplits from '../components/RecentStockSplits';
import type { Transaction } from '../types/transaction';

const TXN_LIMIT = 100;

function isNotFound(err: unknown): boolean {
    return (err as { response?: { status?: number } } | null)?.response?.status === 404;
}

export default function HoldingDetailPage() {
    const { id } = useParams<{ id: string }>();
    const { role } = useAuth();
    const canWrite = hasWriteAccess(role);

    // A 404 resolves to null (rendered as "not found"); any other failure stays an error with a retry.
    const { data: holding, loading: holdingLoading, error: holdingError, refetch: refetchHolding } = useApiQuery(
        () => getHolding(id!).catch((err: unknown) => {
            if (isNotFound(err)) return null;
            throw err;
        }),
    );

    const holdingAccountId = holding?.account_id;
    const holdingSymbol = holding?.symbol;

    const { data: txnPage, loading: txnLoading, error: txnError, refetch: refetchTxns } = useApiQuery(
        () => holdingAccountId && holdingSymbol
            ? listTransactions(holdingAccountId, 0, TXN_LIMIT, holdingSymbol)
            : Promise.resolve(null),
        [holdingAccountId, holdingSymbol],
    );
    // The API does not guarantee an order, so show newest first.
    const transactions: Transaction[] = [...(txnPage?.data ?? [])]
        .sort((a, b) => b.date.localeCompare(a.date) || (b.created_at ?? '').localeCompare(a.created_at ?? ''));
    const totalTxns = txnPage?.total ?? transactions.length;

    // Holdings and transactions are in the account's native currency, which only the account knows.
    const { data: account } = useApiQuery(
        () => holdingAccountId ? getAccount(holdingAccountId) : Promise.resolve(null),
        [holdingAccountId],
    );
    const currency = account?.currency ?? 'USD';
    const money = (value: number) => formatCurrency(value, currency);

    const [editing, setEditing] = useState(false);
    const [editQty, setEditQty] = useState('');
    const [editCostBasis, setEditCostBasis] = useState('');

    function startEdit() {
        if (!holding) return;
        setEditQty(String(holding.quantity));
        setEditCostBasis(String(holding.cost_basis));
        setEditing(true);
    }

    const saveMutation = useApiMutation(
        (input: { holdingId: string; accountId: string; symbol: string; quantity: number; costBasis: number }) => updateHolding(input.holdingId, {
            account_id: input.accountId,
            symbol: input.symbol,
            quantity: input.quantity,
            cost_basis: input.costBasis,
        }),
        {
            successMessage: 'Holding updated',
            onSuccess: () => {
                setEditing(false);
                refetchHolding();
            },
        },
    );

    const editQtyNum = parseFloat(editQty);
    const editCostNum = parseFloat(editCostBasis);
    const editValid = Number.isFinite(editQtyNum) && editQtyNum >= 0
        && Number.isFinite(editCostNum) && editCostNum >= 0;

    function handleSave() {
        if (!holding || !editValid) return;
        void saveMutation.mutate({
            holdingId: holding.id,
            accountId: holding.account_id,
            symbol: holding.symbol,
            quantity: editQtyNum,
            costBasis: editCostNum,
        });
    }

    // Block only on the first load; a refetch keeps the page on screen.
    if (holdingLoading && !holding) return <LoadingState message="Loading holding..." />;
    if (holdingError) return <ErrorState message={holdingError} onRetry={refetchHolding} />;
    if (!holding) return <EmptyState title="Holding not found" />;

    return (
        <div>
            <div style={{ marginBottom: '1.5rem' }}>
                <Link to={`/accounts/${holding.account_id}`} style={{ color: '#1976d2', textDecoration: 'none' }}>Account</Link> / {holding.symbol}
            </div>

            <h2 style={{ marginBottom: '1.5rem' }}>
                {holding.symbol}
                {currency !== 'USD' && <span style={{ color: '#666', fontSize: '1rem', fontWeight: 400, marginLeft: '0.75rem' }}>{currency}</span>}
            </h2>

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
                            <label htmlFor="holding-edit-quantity" style={{ display: 'block', marginBottom: '0.25rem', fontWeight: 'bold' }}>Quantity</label>
                            <input id="holding-edit-quantity" type="number" min="0" step="any" value={editQty} onChange={(e) => setEditQty(e.target.value)}
                                style={{ width: '100%', padding: '0.5rem', border: '1px solid #ccc', borderRadius: '4px' }} />
                        </div>
                        <div>
                            <label htmlFor="holding-edit-cost-basis" style={{ display: 'block', marginBottom: '0.25rem', fontWeight: 'bold' }}>Cost Basis</label>
                            <CurrencyInput id="holding-edit-cost-basis" value={editCostBasis} onChange={setEditCostBasis}
                                style={{ width: '100%', padding: '0.5rem', border: '1px solid #ccc', borderRadius: '4px' }} />
                        </div>
                        <div style={{ gridColumn: '1 / -1' }}>
                            <Button onClick={handleSave} disabled={!editValid} style={{ background: '#2e7d32', marginRight: '0.5rem' }}>Save</Button>
                            {!editValid && (
                                <span role="alert" style={{ color: '#c62828', fontSize: '0.85rem', marginRight: '0.5rem' }}>
                                    Quantity and cost basis must be numbers of zero or more.
                                </span>
                            )}
                            <Button onClick={() => setEditing(false)} style={{ background: '#666' }}>Cancel</Button>
                        </div>
                    </div>
                ) : (
                    <table style={{ borderCollapse: 'collapse' }}>
                        <tbody>
                            <tr><td style={{ padding: '0.5rem 1rem 0.5rem 0', fontWeight: 'bold' }}>Symbol</td><td style={{ padding: '0.5rem 0' }}>{holding.symbol}</td></tr>
                            <tr><td style={{ padding: '0.5rem 1rem 0.5rem 0', fontWeight: 'bold' }}>Quantity</td><td style={{ padding: '0.5rem 0' }}>{holding.quantity}</td></tr>
                            <tr><td style={{ padding: '0.5rem 1rem 0.5rem 0', fontWeight: 'bold' }}>Cost Basis</td><td style={{ padding: '0.5rem 0' }}>{money(holding.cost_basis)}</td></tr>
                            <tr><td style={{ padding: '0.5rem 1rem 0.5rem 0', fontWeight: 'bold' }}>Manual Override</td><td style={{ padding: '0.5rem 0' }}>{holding.is_manual_override ? 'Yes' : 'No'}</td></tr>
                            {holding.is_money_market && (
                                <tr><td style={{ padding: '0.5rem 1rem 0.5rem 0', fontWeight: 'bold' }}>Money Market Rate</td><td style={{ padding: '0.5rem 0' }}>{holding.money_market_rate != null ? `${holding.money_market_rate}%` : '-'}</td></tr>
                            )}
                            <tr><td style={{ padding: '0.5rem 1rem 0.5rem 0', fontWeight: 'bold' }}>As Of Date</td><td style={{ padding: '0.5rem 0' }}>{formatDate(holding.as_of_date)}</td></tr>
                        </tbody>
                    </table>
                )}
            </div>

            <div style={{ marginBottom: '2rem' }}>
                <RecentStockSplits symbol={holding.symbol} title={`Splits affecting ${holding.symbol}`} />
            </div>

            <div style={cardStyle}>
                <h3 style={{ marginBottom: '1rem' }}>Transactions for {holding.symbol}</h3>
                {txnLoading ? (
                    <LoadingState message="Loading transactions..." />
                ) : txnError ? (
                    <ErrorState message={txnError} onRetry={refetchTxns} />
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
                                    <td style={tdStyle}>{formatDate(txn.date)}</td>
                                    <td style={tdStyle}>{capitalize(txn.type)}</td>
                                    <td style={{ ...tdStyle, textAlign: 'right' }}>{txn.quantity ?? '-'}</td>
                                    <td style={{ ...tdStyle, textAlign: 'right' }}>{money(txn.amount)}</td>
                                </tr>
                            ))}
                            {transactions.length === 0 && (
                                <tr><td colSpan={4}><EmptyState title={`No transactions for ${holding.symbol}`} /></td></tr>
                            )}
                        </tbody>
                    </table>
                )}
                {!txnLoading && !txnError && totalTxns > transactions.length && (
                    <div style={{ marginTop: '1rem', color: '#666', fontSize: '0.9rem' }}>
                        Showing the {transactions.length} most recent of {totalTxns} transactions for {holding.symbol}
                    </div>
                )}
            </div>
        </div>
    );
}
