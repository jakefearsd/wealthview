import { useState } from 'react';
import { Link } from 'react-router-dom';
import { listAccounts, createAccount, updateAccount, deleteAccount } from '../api/accounts';
import { useApiQuery } from '../hooks/useApiQuery';
import { useAuth } from '../context/AuthContext';
import type { Account, AccountRequest } from '../types/account';
import { cardStyle } from '../utils/styles';
import toast from 'react-hot-toast';

export default function AccountsListPage() {
    const { role } = useAuth();
    const canWrite = role === 'admin' || role === 'member';
    const [showForm, setShowForm] = useState(false);
    const [editingId, setEditingId] = useState<string | null>(null);
    const [name, setName] = useState('');
    const [type, setType] = useState('brokerage');
    const [institution, setInstitution] = useState('');
    const { data, loading, error, refetch } = useApiQuery(() => listAccounts(0, 100));

    function resetForm() {
        setName('');
        setType('brokerage');
        setInstitution('');
        setEditingId(null);
        setShowForm(false);
    }

    function startEdit(account: Account) {
        setName(account.name);
        setType(account.type);
        setInstitution(account.institution ?? '');
        setEditingId(account.id);
        setShowForm(true);
    }

    async function handleSave() {
        const request: AccountRequest = { name, type, institution: institution || undefined };
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

    if (loading) return <div>Loading accounts...</div>;
    if (error) return <div style={{ color: '#d32f2f' }}>Error: {error}</div>;

    return (
        <div>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '1.5rem' }}>
                <h2>Accounts</h2>
                {canWrite && <button onClick={() => setShowForm(true)} style={{ padding: '0.5rem 1rem', background: '#1976d2', color: '#fff', border: 'none', borderRadius: '4px', cursor: 'pointer' }}>New Account</button>}
            </div>

            {showForm && (
                <div style={{ ...cardStyle, marginBottom: '1.5rem' }}>
                    <h3 style={{ marginBottom: '1rem' }}>{editingId ? 'Edit Account' : 'Create Account'}</h3>
                    <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr 1fr', gap: '1rem' }}>
                        <input placeholder="Name" value={name} onChange={(e) => setName(e.target.value)} style={{ padding: '0.5rem', border: '1px solid #ccc', borderRadius: '4px' }} />
                        <select value={type} onChange={(e) => setType(e.target.value)} style={{ padding: '0.5rem', border: '1px solid #ccc', borderRadius: '4px' }}>
                            <option value="brokerage">Brokerage</option>
                            <option value="ira">IRA</option>
                            <option value="401k">401(k)</option>
                            <option value="roth">Roth IRA</option>
                            <option value="bank">Bank</option>
                        </select>
                        <input placeholder="Institution" value={institution} onChange={(e) => setInstitution(e.target.value)} style={{ padding: '0.5rem', border: '1px solid #ccc', borderRadius: '4px' }} />
                    </div>
                    <div style={{ marginTop: '1rem', display: 'flex', gap: '0.5rem' }}>
                        <button onClick={handleSave} style={{ padding: '0.5rem 1rem', background: '#2e7d32', color: '#fff', border: 'none', borderRadius: '4px', cursor: 'pointer' }}>{editingId ? 'Save' : 'Create'}</button>
                        <button onClick={resetForm} style={{ padding: '0.5rem 1rem', background: '#eee', border: 'none', borderRadius: '4px', cursor: 'pointer' }}>Cancel</button>
                    </div>
                </div>
            )}

            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(280px, 1fr))', gap: '1rem' }}>
                {data?.data.map((account) => (
                    <div key={account.id} style={cardStyle}>
                        <Link to={`/accounts/${account.id}`} style={{ textDecoration: 'none', color: 'inherit' }}>
                            <h3 style={{ marginBottom: '0.5rem' }}>{account.name}</h3>
                            <div style={{ fontSize: '1.25rem', fontWeight: 600, marginBottom: '0.5rem' }}>
                                ${account.balance.toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 })}
                            </div>
                            <div style={{ color: '#666', fontSize: '0.9rem' }}>{account.type} {account.institution ? `- ${account.institution}` : ''}</div>
                        </Link>
                        {canWrite && (
                            <div style={{ marginTop: '1rem', display: 'flex', gap: '0.5rem' }}>
                                <button onClick={() => startEdit(account)} style={{ padding: '0.3rem 0.6rem', background: '#1976d2', color: '#fff', border: 'none', borderRadius: '4px', cursor: 'pointer', fontSize: '0.8rem' }}>Edit</button>
                                <button onClick={() => handleDelete(account.id)} style={{ padding: '0.3rem 0.6rem', background: '#d32f2f', color: '#fff', border: 'none', borderRadius: '4px', cursor: 'pointer', fontSize: '0.8rem' }}>Delete</button>
                            </div>
                        )}
                    </div>
                ))}
                {data?.data.length === 0 && <div style={{ color: '#999' }}>No accounts yet. Create one to get started.</div>}
            </div>
        </div>
    );
}
