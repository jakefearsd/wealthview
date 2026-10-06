import { useState } from 'react';
import { useParams, Link } from 'react-router';
import { getAccount } from '../api/accounts';
import { listTransactions, deleteTransaction } from '../api/transactions';
import { listHoldings, updateHolding } from '../api/holdings';
import { useApiQuery } from '../hooks/useApiQuery';
import { useApiMutation } from '../hooks/useApiMutation';
import { useAuth } from '../context/AuthContext';
import { hasWriteAccess } from '../utils/permissions';
import { formatCurrency, formatDate } from '../utils/format';
import { accountTypeLabel, capitalize } from '../utils/accountTypes';
import CurrencyInput from '../components/CurrencyInput';
import { cardStyle, tableStyle, thStyle, tdStyle, trHoverStyle } from '../utils/styles';
import LoadingState from '../components/LoadingState';
import EmptyState from '../components/EmptyState';
import ErrorState from '../components/ErrorState';
import TheoreticalPortfolioChart from '../components/TheoreticalPortfolioChart';
import TransactionForm from '../components/TransactionForm';
import Button from '../components/Button';
import type { Transaction } from '../types/transaction';

const TXN_PAGE_SIZE = 50;

function isNotFound(err: unknown): boolean {
    return (err as { response?: { status?: number } } | null)?.response?.status === 404;
}

/** Newest first; the API does not guarantee an order, so every loaded page is sorted client-side. */
function byDateDesc(a: Transaction, b: Transaction): number {
    return b.date.localeCompare(a.date) || (b.created_at ?? '').localeCompare(a.created_at ?? '');
}

export default function AccountDetailPage() {
    const { id } = useParams<{ id: string }>();
    const { role } = useAuth();
    const canWrite = hasWriteAccess(role);

    // A 404 resolves to null (rendered as "not found"); any other failure stays an error with a retry.
    const { data: account, loading: acctLoading, error: acctError, refetch: refetchAccount } = useApiQuery(
        () => getAccount(id!).catch((err: unknown) => {
            if (isNotFound(err)) return null;
            throw err;
        }),
    );
    const { data: holdings, loading: holdLoading, error: holdError, refetch: refetchHoldings } = useApiQuery(() => listHoldings(id!));
    const { data: txnPage, loading: txnLoading, error: txnError, refetch: fetchFirstTxnPage } = useApiQuery(
        () => listTransactions(id!, 0, TXN_PAGE_SIZE),
    );
    const currency = account?.currency ?? 'USD';
    const money = (value: number) => formatCurrency(value, currency);

    // Pages after the first are appended here; a refetch of page 0 resets them.
    const [extraTxns, setExtraTxns] = useState<Transaction[]>([]);
    const [pagesLoaded, setPagesLoaded] = useState(1);

    function refetchTxns() {
        setExtraTxns([]);
        setPagesLoaded(1);
        fetchFirstTxnPage();
    }

    const loadMoreMutation = useApiMutation(
        (page: number) => listTransactions(id!, page, TXN_PAGE_SIZE),
        {
            onSuccess: (result, page) => {
                setExtraTxns((prev) => [...prev, ...result.data]);
                setPagesLoaded(page + 1);
            },
        },
    );

    const transactions = (() => {
        const seen = new Set<string>();
        return [...(txnPage?.data ?? []), ...extraTxns]
            .filter((t) => !seen.has(t.id) && seen.add(t.id))
            .sort(byDateDesc);
    })();
    const totalTxns = txnPage?.total ?? transactions.length;

    const [showAdd, setShowAdd] = useState(false);
    const [editingTxnId, setEditingTxnId] = useState<string | null>(null);
    const [editingHoldingId, setEditingHoldingId] = useState<string | null>(null);
    const [editQty, setEditQty] = useState('');
    const [editCostBasis, setEditCostBasis] = useState('');

    const deleteTxnMutation = useApiMutation(
        (txnId: string) => deleteTransaction(txnId),
        {
            successMessage: 'Transaction deleted',
            onSuccess: () => refetchTxns(),
        },
    );

    function handleDeleteTxn(txnId: string) {
        if (!window.confirm('Delete this transaction? Holdings and balances will be recalculated.')) return;
        void deleteTxnMutation.mutate(txnId);
    }

    function startEditHolding(h: { id: string; quantity: number; cost_basis: number }) {
        setEditingHoldingId(h.id);
        setEditQty(String(h.quantity));
        setEditCostBasis(String(h.cost_basis));
    }

    const saveHoldingMutation = useApiMutation(
        (input: { holdingId: string; symbol: string }) => updateHolding(input.holdingId, {
            account_id: id!,
            symbol: input.symbol,
            quantity: parseFloat(editQty),
            cost_basis: parseFloat(editCostBasis),
        }),
        {
            successMessage: 'Holding updated',
            onSuccess: () => {
                setEditingHoldingId(null);
                refetchHoldings();
            },
        },
    );

    const editQtyNum = parseFloat(editQty);
    const editCostNum = parseFloat(editCostBasis);
    const holdingEditValid = Number.isFinite(editQtyNum) && editQtyNum >= 0
        && Number.isFinite(editCostNum) && editCostNum >= 0;

    function handleSaveHolding(holdingId: string, symbol: string) {
        if (!holdingEditValid) return;
        void saveHoldingMutation.mutate({ holdingId, symbol });
    }

    // Block only on the first load; a refetch keeps the stale page (and the chart's state) on screen.
    if ((acctLoading && !account) || (holdLoading && !holdings) || (txnLoading && !txnPage)) {
        return <LoadingState message="Loading account details..." />;
    }
    if (acctError) return <ErrorState message={acctError} onRetry={refetchAccount} />;
    if (!account) {
        return (
            <EmptyState
                title="Account not found"
                action={<Link to="/accounts" style={{ color: '#1976d2' }}>Back to accounts</Link>}
            />
        );
    }

    return (
        <div>
            <div style={{ marginBottom: '1.5rem' }}>
                <Link to="/accounts" style={{ color: '#1976d2', textDecoration: 'none' }}>Accounts</Link> / {account.name}
            </div>
            <h2 style={{ marginBottom: '0.5rem' }}>{account.name}</h2>
            <div style={{ color: '#666', marginBottom: '2rem' }}>
                {accountTypeLabel(account.type)}{account.institution ? ` - ${account.institution}` : ''}
                {account.currency !== 'USD' ? ` · ${account.currency}` : ''}
            </div>

            <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '1.5rem', marginBottom: '2rem' }}>
                <div style={cardStyle}>
                    <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '1rem' }}>
                        <h3>Holdings</h3>
                    </div>
                    {holdError ? (
                        <ErrorState message={holdError} onRetry={refetchHoldings} />
                    ) : (
                    <table style={tableStyle}>
                        <thead>
                            <tr>
                                <th style={thStyle}>Symbol</th>
                                <th style={{ ...thStyle, textAlign: 'right' }}>Qty</th>
                                <th style={{ ...thStyle, textAlign: 'right' }}>Price</th>
                                <th style={{ ...thStyle, textAlign: 'right' }}>Cost Basis</th>
                                <th style={{ ...thStyle, textAlign: 'right' }}>Market Value</th>
                                <th style={{ ...thStyle, textAlign: 'right' }}>Gain/Loss</th>
                                {canWrite && <th style={thStyle}></th>}
                            </tr>
                        </thead>
                        <tbody>
                            {holdings?.map((h) => {
                                const glColor = h.gain_loss != null ? (h.gain_loss >= 0 ? '#2e7d32' : '#c62828') : '#888';
                                return (
                                <tr key={h.id} style={trHoverStyle}>
                                    <td style={tdStyle}>
                                        <Link to={`/holdings/${h.id}`} style={{ color: '#1976d2', textDecoration: 'none' }}>
                                            {h.symbol}
                                        </Link>
                                        {h.is_money_market && <span style={{ color: '#999', fontSize: '0.8rem', marginLeft: '0.25rem' }}>(MM)</span>}
                                    </td>
                                    {editingHoldingId === h.id ? (
                                        <>
                                            <td style={{ ...tdStyle, textAlign: 'right' }}>
                                                <input type="number" min="0" step="any" aria-label={`Quantity for ${h.symbol}`} value={editQty} onChange={(e) => setEditQty(e.target.value)} style={{ width: '80px', padding: '0.25rem', textAlign: 'right' }} />
                                            </td>
                                            <td style={{ ...tdStyle, textAlign: 'right', color: '#888' }}>{h.current_price != null ? money(h.current_price) : '—'}</td>
                                            <td style={{ ...tdStyle, textAlign: 'right' }}>
                                                <CurrencyInput aria-label={`Cost basis for ${h.symbol}`} value={editCostBasis} onChange={setEditCostBasis} style={{ width: '100px', padding: '0.25rem', textAlign: 'right' }} />
                                            </td>
                                            <td style={{ ...tdStyle, textAlign: 'right', color: '#888' }}>{h.market_value != null ? money(h.market_value) : '—'}</td>
                                            <td style={{ ...tdStyle, textAlign: 'right', color: glColor }}>{h.gain_loss != null ? money(h.gain_loss) : '—'}</td>
                                            <td style={{ ...tdStyle, textAlign: 'center' }}>
                                                <button onClick={() => handleSaveHolding(h.id, h.symbol)} disabled={!holdingEditValid} title={holdingEditValid ? undefined : 'Quantity and cost basis must be numbers of zero or more'} style={{ background: 'none', border: 'none', color: '#2e7d32', cursor: holdingEditValid ? 'pointer' : 'not-allowed', opacity: holdingEditValid ? 1 : 0.5, marginRight: '0.25rem' }}>Save</button>
                                                <button onClick={() => setEditingHoldingId(null)} style={{ background: 'none', border: 'none', color: '#666', cursor: 'pointer' }}>Cancel</button>
                                            </td>
                                        </>
                                    ) : (
                                        <>
                                            <td style={{ ...tdStyle, textAlign: 'right' }}>{h.quantity}</td>
                                            <td style={{ ...tdStyle, textAlign: 'right', color: '#888' }}>{h.current_price != null ? money(h.current_price) : '—'}</td>
                                            <td style={{ ...tdStyle, textAlign: 'right' }}>{money(h.cost_basis)}</td>
                                            <td style={{ ...tdStyle, textAlign: 'right' }}>{h.market_value != null ? money(h.market_value) : '—'}</td>
                                            <td style={{ ...tdStyle, textAlign: 'right', color: glColor }}>{h.gain_loss != null ? money(h.gain_loss) : '—'}</td>
                                            {canWrite && (
                                                <td style={{ ...tdStyle, textAlign: 'center' }}>
                                                    <button onClick={() => startEditHolding(h)} style={{ background: 'none', border: 'none', color: '#1976d2', cursor: 'pointer' }}>Edit</button>
                                                </td>
                                            )}
                                        </>
                                    )}
                                </tr>
                                );
                            })}
                            {holdings && holdings.length > 0 && (() => {
                                const totalCost = holdings.reduce((sum, h) => sum + (h.cost_basis ?? 0), 0);
                                const totalValue = holdings.reduce((sum, h) => sum + (h.market_value ?? h.cost_basis ?? 0), 0);
                                const totalGL = totalValue - totalCost;
                                const glColor = totalGL >= 0 ? '#2e7d32' : '#c62828';
                                return (
                                    <tr style={{ borderTop: '2px solid #ccc', fontWeight: 600 }}>
                                        <td style={tdStyle}>Total</td>
                                        <td style={tdStyle}></td>
                                        <td style={tdStyle}></td>
                                        <td style={{ ...tdStyle, textAlign: 'right' }}>{money(totalCost)}</td>
                                        <td style={{ ...tdStyle, textAlign: 'right' }}>{money(totalValue)}</td>
                                        <td style={{ ...tdStyle, textAlign: 'right', color: glColor }}>{money(totalGL)}</td>
                                        {canWrite && <td style={tdStyle}></td>}
                                    </tr>
                                );
                            })()}
                            {holdings?.length === 0 && <tr><td colSpan={canWrite ? 7 : 6}><EmptyState title="No holdings" /></td></tr>}
                        </tbody>
                    </table>
                    )}
                </div>

                <div>
                    {canWrite && (
                        <Link to={`/accounts/${id}/import`} style={{ display: 'inline-block', padding: '0.5rem 1rem', background: '#1976d2', color: '#fff', borderRadius: '4px', textDecoration: 'none', marginBottom: '1rem' }}>
                            Import
                        </Link>
                    )}
                </div>
            </div>

            {account.type !== 'bank' && (
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

                {txnError ? (
                    <ErrorState message={txnError} onRetry={refetchTxns} />
                ) : (
                <>
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
                        {transactions.map((txn) => (
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
                                    <td style={tdStyle}>{formatDate(txn.date)}</td>
                                    <td style={tdStyle}>{capitalize(txn.type)}</td>
                                    <td style={tdStyle}>{txn.symbol || '-'}</td>
                                    <td style={{ ...tdStyle, textAlign: 'right' }}>{txn.quantity ?? '-'}</td>
                                    <td style={{ ...tdStyle, textAlign: 'right' }}>{money(txn.amount)}</td>
                                    {canWrite && (
                                        <td style={{ ...tdStyle, textAlign: 'center' }}>
                                            <button onClick={() => setEditingTxnId(txn.id)} aria-label={`Edit ${txn.type} transaction`} style={{ background: 'none', border: 'none', color: '#1976d2', cursor: 'pointer', marginRight: '0.5rem' }}>Edit</button>
                                            <button onClick={() => handleDeleteTxn(txn.id)} style={{ background: 'none', border: 'none', color: '#d32f2f', cursor: 'pointer' }}>Delete</button>
                                        </td>
                                    )}
                                </tr>
                            )
                        ))}
                        {transactions.length === 0 && <tr><td colSpan={canWrite ? 6 : 5}><EmptyState title="No transactions" /></td></tr>}
                    </tbody>
                </table>
                {totalTxns > transactions.length && (
                    <div style={{ display: 'flex', alignItems: 'center', gap: '1rem', marginTop: '1rem', color: '#666', fontSize: '0.9rem' }}>
                        <span>Showing {transactions.length} of {totalTxns} transactions</span>
                        <Button
                            variant="secondary"
                            size="sm"
                            onClick={() => void loadMoreMutation.mutate(pagesLoaded)}
                            disabled={loadMoreMutation.loading}
                        >
                            {loadMoreMutation.loading ? 'Loading...' : 'Load more'}
                        </Button>
                    </div>
                )}
                </>
                )}
            </div>
        </div>
    );
}
