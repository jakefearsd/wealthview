import { useState } from 'react';
import { Link } from 'react-router';
import { listAccounts, createAccount, updateAccount, deleteAccount } from '../api/accounts';
import { useApiQuery } from '../hooks/useApiQuery';
import { useAuth } from '../context/AuthContext';
import type { Account, AccountRequest } from '../types/account';
import { cardStyle } from '../utils/styles';
import { formatCurrency } from '../utils/format';
import LoadingState from '../components/LoadingState';
import ErrorState from '../components/ErrorState';
import EmptyState from '../components/EmptyState';
import Button from '../components/Button';
import toast from 'react-hot-toast';

export default function AccountsListPage() {
    const { role } = useAuth();
    const canWrite = role === 'admin' || role === 'member';
    const [showForm, setShowForm] = useState(false);
    const [editingId, setEditingId] = useState<string | null>(null);
    const [name, setName] = useState('');
    const [type, setType] = useState('brokerage');
    const [institution, setInstitution] = useState('');
    const [currency, setCurrency] = useState('USD');
    const { data, loading, error, refetch } = useApiQuery(() => listAccounts(0, 100));

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

    async function handleSave() {
        const request: AccountRequest = {
            name,
            type,
            institution: institution || undefined,
            currency: currency || 'USD',
        };
        try {
            if (editingId) {
                await updateAccount(editingId, request);
                toast.success('Account updated');
            } else {
                await createAccount(request);
                toast.success('Account created');
            }
            resetForm();
            refetch();
        } catch {
            toast.error(editingId ? 'Failed to update account' : 'Failed to create account');
        }
    }

    async function handleDelete(id: string) {
        if (!confirm('Delete this account?')) return;
        try {
            await deleteAccount(id);
            toast.success('Account deleted');
            refetch();
        } catch {
            toast.error('Failed to delete account');
        }
    }

    if (loading) return <LoadingState message="Loading accounts..." />;
    if (error) return <ErrorState message={error} onRetry={refetch} />;

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
                        <input placeholder="Name" value={name} onChange={(e) => setName(e.target.value)} style={{ padding: '0.5rem', border: '1px solid #ccc', borderRadius: '4px' }} />
                        <select value={type} onChange={(e) => setType(e.target.value)} style={{ padding: '0.5rem', border: '1px solid #ccc', borderRadius: '4px' }}>
                            <option value="brokerage">Brokerage</option>
                            <option value="ira">IRA</option>
                            <option value="401k">401(k)</option>
                            <option value="roth">Roth IRA</option>
                            <option value="bank">Bank</option>
                        </select>
                        <input placeholder="Institution" value={institution} onChange={(e) => setInstitution(e.target.value)} style={{ padding: '0.5rem', border: '1px solid #ccc', borderRadius: '4px' }} />
                        <input
                            placeholder="Currency (e.g. USD, EUR)"
                            value={currency}
                            onChange={(e) => setCurrency(e.target.value.toUpperCase())}
                            maxLength={3}
                            style={{ padding: '0.5rem', border: '1px solid #ccc', borderRadius: '4px' }}
                        />
                    </div>
                    <div style={{ marginTop: '1rem', display: 'flex', gap: '0.5rem' }}>
                        <Button onClick={handleSave} style={{ background: '#2e7d32' }}>{editingId ? 'Save' : 'Create'}</Button>
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
                                    color: account.type === 'roth' ? '#2e7d32' : account.type === 'ira' || account.type === '401k' ? '#e65100' : account.type === 'bank' ? '#6a1b9a' : '#1565c0',
                                    borderRadius: '4px', fontSize: '0.75rem', fontWeight: 600, whiteSpace: 'nowrap',
                                }}>
                                    {account.type === '401k' ? '401(k)' : account.type === 'ira' ? 'IRA' : account.type === 'roth' ? 'Roth IRA' : account.type.charAt(0).toUpperCase() + account.type.slice(1)}
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
                                <div>
                                    <div style={{ color: '#999', fontSize: '0.75rem', marginBottom: '0.15rem' }}>Institution</div>
                                    <div style={{ color: '#444' }}>{account.institution || 'Not specified'}</div>
                                </div>
                                <div>
                                    <div style={{ color: '#999', fontSize: '0.75rem', marginBottom: '0.15rem' }}>Created</div>
                                    <div style={{ color: '#444' }}>{new Date(account.created_at).toLocaleDateString()}</div>
                                </div>
                            </div>
                        </Link>
                        {canWrite && (
                            <div style={{ marginTop: '1rem', paddingTop: '0.75rem', borderTop: '1px solid #eee', display: 'flex', gap: '0.5rem' }}>
                                <Button onClick={() => startEdit(account)} size="sm">Edit</Button>
                                <Button onClick={() => handleDelete(account.id)} variant="danger" size="sm">Delete</Button>
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
