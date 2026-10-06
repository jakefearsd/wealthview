import { useState } from 'react';
import { Link } from 'react-router';
import { listAccounts, createAccount, updateAccount, deleteAccount } from '../api/accounts';
import { useApiQuery } from '../hooks/useApiQuery';
import { useApiMutation } from '../hooks/useApiMutation';
import { useAuth } from '../context/AuthContext';
import { hasWriteAccess } from '../utils/permissions';
import type { Account, AccountRequest } from '../types/account';
import { cardStyle, inputFieldStyle, selectStyle } from '../utils/styles';
import { formatCurrency, formatDate } from '../utils/format';
import { accountTypeLabel } from '../utils/accountTypes';
import LoadingState from '../components/LoadingState';
import ErrorState from '../components/ErrorState';
import EmptyState from '../components/EmptyState';
import Button from '../components/Button';
import StatTile from '../components/StatTile';

const CURRENCY_CODE = /^[A-Z]{3}$/;

export default function AccountsListPage() {
    const { role } = useAuth();
    const canWrite = hasWriteAccess(role);
    const [showForm, setShowForm] = useState(false);
    const [editingId, setEditingId] = useState<string | null>(null);
    const [name, setName] = useState('');
    const [type, setType] = useState('brokerage');
    const [institution, setInstitution] = useState('');
    const [currency, setCurrency] = useState('USD');
    const { data, loading, error, refetch } = useApiQuery(() => listAccounts(0, 100));

    const trimmedCurrency = currency.trim();
    // A blank currency falls back to USD on save; anything else must be a 3-letter ISO-4217-shaped code,
    // because a malformed one (e.g. "US") makes Intl.NumberFormat throw when the balance is rendered.
    const currencyValid = trimmedCurrency === '' || CURRENCY_CODE.test(trimmedCurrency);
    const nameValid = name.trim() !== '';
    const canSubmit = nameValid && currencyValid;

    function resetForm() {
        setName('');
        setType('brokerage');
        setInstitution('');
        setCurrency('USD');
        setEditingId(null);
        setShowForm(false);
    }

    function startEdit(account: Account) {
        setName(account.name);
        setType(account.type);
        setInstitution(account.institution ?? '');
        setCurrency(account.currency ?? 'USD');
        setEditingId(account.id);
        setShowForm(true);
    }

    const saveMutation = useApiMutation(
        (input: { id: string | null; request: AccountRequest }) => (
            input.id ? updateAccount(input.id, input.request) : createAccount(input.request)
        ),
        {
            successMessage: (_result, input) => (input.id ? 'Account updated' : 'Account created'),
            onSuccess: () => {
                resetForm();
                refetch();
            },
        },
    );

    function handleSave() {
        if (!canSubmit) return;
        const request: AccountRequest = {
            name: name.trim(),
            type,
            institution: institution.trim() || undefined,
            currency: trimmedCurrency || 'USD',
        };
        void saveMutation.mutate({ id: editingId, request });
    }

    const deleteMutation = useApiMutation(
        (id: string) => deleteAccount(id),
        {
            successMessage: 'Account deleted',
            onSuccess: () => refetch(),
        },
    );

    function handleDelete(account: Account) {
        if (!confirm(
            `Delete "${account.name}" and all of its transactions and holdings? This cannot be undone.`,
        )) return;
        void deleteMutation.mutate(account.id);
    }

    // Only block on the first load: a refetch keeps the stale list on screen instead of blanking the page.
    if (loading && !data) return <LoadingState message="Loading accounts..." />;
    if (error && !data) return <ErrorState message={error} onRetry={refetch} />;

    return (
        <div>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '1.5rem' }}>
                <h2>Accounts</h2>
                {canWrite && <Button onClick={() => setShowForm(true)}>New Account</Button>}
            </div>

            {showForm && (
                <div style={{ ...cardStyle, marginBottom: '1.5rem' }}>
                    <h3 style={{ marginBottom: '1rem' }}>{editingId ? 'Edit Account' : 'Create Account'}</h3>
                    <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr 1fr 1fr', gap: '1rem' }}>
                        <input placeholder="Name" aria-label="Account name" value={name} onChange={(e) => setName(e.target.value)} style={inputFieldStyle} />
                        <select aria-label="Account type" value={type} onChange={(e) => setType(e.target.value)} style={selectStyle}>
                            <option value="brokerage">Brokerage</option>
                            <option value="ira">IRA</option>
                            <option value="401k">401(k)</option>
                            <option value="roth">Roth IRA</option>
                            <option value="bank">Bank</option>
                        </select>
                        <input placeholder="Institution" aria-label="Institution" value={institution} onChange={(e) => setInstitution(e.target.value)} style={inputFieldStyle} />
                        <input
                            placeholder="Currency (e.g. USD, EUR)"
                            aria-label="Currency"
                            aria-invalid={!currencyValid}
                            value={currency}
                            onChange={(e) => setCurrency(e.target.value.toUpperCase())}
                            maxLength={3}
                            style={inputFieldStyle}
                        />
                    </div>
                    {!currencyValid && (
                        <div role="alert" style={{ color: '#c62828', fontSize: '0.85rem', marginTop: '0.5rem' }}>
                            Currency must be a 3-letter code such as USD or EUR.
                        </div>
                    )}
                    <div style={{ marginTop: '1rem', display: 'flex', gap: '0.5rem' }}>
                        <Button onClick={handleSave} disabled={!canSubmit} style={{ background: '#2e7d32' }}>{editingId ? 'Save' : 'Create'}</Button>
                        <Button onClick={resetForm} variant="secondary" style={{ background: '#eee', color: '#333', border: 'none' }}>Cancel</Button>
                    </div>
                </div>
            )}

            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(360px, 1fr))', gap: '1rem' }}>
                {data?.data.map((account) => (
                    <div key={account.id} style={cardStyle}>
                        <Link to={`/accounts/${account.id}`} style={{ textDecoration: 'none', color: 'inherit' }}>
                            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', marginBottom: '1rem' }}>
                                <h3 style={{ margin: 0 }}>{account.name}</h3>
                                <span style={{
                                    padding: '0.2rem 0.6rem',
                                    background: account.type === 'roth' ? '#e8f5e9' : account.type === 'ira' || account.type === '401k' ? '#fff3e0' : account.type === 'bank' ? '#f3e5f5' : '#e3f2fd',
                                    color: account.type === 'roth' ? '#2e7d32' : account.type === 'ira' || account.type === '401k' ? '#bf360c' : account.type === 'bank' ? '#6a1b9a' : '#1565c0',
                                    borderRadius: '4px', fontSize: '0.75rem', fontWeight: 600, whiteSpace: 'nowrap',
                                }}>
                                    {accountTypeLabel(account.type)}
                                </span>
                                {account.currency !== 'USD' && (
                                    <span style={{
                                        padding: '0.2rem 0.6rem',
                                        background: '#fce4ec',
                                        color: '#c62828',
                                        borderRadius: '4px',
                                        fontSize: '0.75rem',
                                        fontWeight: 600,
                                        marginLeft: '0.5rem',
                                    }}>
                                        {account.currency}
                                    </span>
                                )}
                            </div>
                            <div style={{ fontSize: '1.5rem', fontWeight: 700, marginBottom: '1rem', color: '#1b5e20' }}>
                                {formatCurrency(account.balance, account.currency)}
                            </div>
                            <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '0.75rem', fontSize: '0.9rem' }}>
                                <StatTile label="Institution" value={account.institution || 'Not specified'} />
                                <StatTile label="Created" value={formatDate(account.created_at)} />
                            </div>
                        </Link>
                        {canWrite && (
                            <div style={{ marginTop: '1rem', paddingTop: '0.75rem', borderTop: '1px solid #eee', display: 'flex', gap: '0.5rem' }}>
                                <Button onClick={() => startEdit(account)} size="sm">Edit</Button>
                                <Button onClick={() => handleDelete(account)} variant="danger" size="sm">Delete</Button>
                            </div>
                        )}
                    </div>
                ))}
                {data?.data.length === 0 && (
                    <EmptyState
                        title="No accounts"
                        message="Create one to get started."
                    />
                )}
            </div>
        </div>
    );
}
