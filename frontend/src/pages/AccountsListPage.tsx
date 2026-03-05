import { useState } from 'react';
import { Link } from 'react-router-dom';
import { listAccounts, createAccount, deleteAccount } from '../api/accounts';
import { useApiQuery } from '../hooks/useApiQuery';
import { useAuth } from '../context/AuthContext';
import type { AccountRequest } from '../types/account';
import toast from 'react-hot-toast';

export default function AccountsListPage() {
    const { role } = useAuth();
    const canWrite = role === 'admin' || role === 'member';
    const [showCreate, setShowCreate] = useState(false);
    const [name, setName] = useState('');
    const [type, setType] = useState('brokerage');
    const [institution, setInstitution] = useState('');
    const { data, loading, error, refetch } = useApiQuery(() => listAccounts(0, 100));

    async function handleCreate() {
        const request: AccountRequest = { name, type, institution: institution || undefined };
        try {
            await createAccount(request);
            toast.success('Account created');
            setShowCreate(false);
            setName('');
            setInstitution('');
            refetch();
        } catch {
            toast.error('Failed to create account');
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
                {canWrite && <button onClick={() => setShowCreate(true)} style={{ padding: '0.5rem 1rem', background: '#1976d2', color: '#fff', border: 'none', borderRadius: '4px', cursor: 'pointer' }}>New Account</button>}
            </div>

            {showCreate && (
                <div style={{ background: '#fff', padding: '1.5rem', borderRadius: '8px', marginBottom: '1.5rem', boxShadow: '0 1px 3px rgba(0,0,0,0.1)' }}>
                    <h3 style={{ marginBottom: '1rem' }}>Create Account</h3>
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
                        <button onClick={handleCreate} style={{ padding: '0.5rem 1rem', background: '#2e7d32', color: '#fff', border: 'none', borderRadius: '4px', cursor: 'pointer' }}>Create</button>
                        <button onClick={() => setShowCreate(false)} style={{ padding: '0.5rem 1rem', background: '#eee', border: 'none', borderRadius: '4px', cursor: 'pointer' }}>Cancel</button>
                    </div>
                </div>
            )}

            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(280px, 1fr))', gap: '1rem' }}>
                {data?.data.map((account) => (
                    <div key={account.id} style={{ background: '#fff', padding: '1.5rem', borderRadius: '8px', boxShadow: '0 1px 3px rgba(0,0,0,0.1)' }}>
                        <Link to={`/accounts/${account.id}`} style={{ textDecoration: 'none', color: 'inherit' }}>
                            <h3 style={{ marginBottom: '0.5rem' }}>{account.name}</h3>
                            <div style={{ color: '#666', fontSize: '0.9rem' }}>{account.type} {account.institution ? `- ${account.institution}` : ''}</div>
                        </Link>
                        {canWrite && (
                            <button onClick={() => handleDelete(account.id)} style={{ marginTop: '1rem', padding: '0.3rem 0.6rem', background: '#d32f2f', color: '#fff', border: 'none', borderRadius: '4px', cursor: 'pointer', fontSize: '0.8rem' }}>Delete</button>
                        )}
                    </div>
                ))}
                {data?.data.length === 0 && <div style={{ color: '#999' }}>No accounts yet. Create one to get started.</div>}
            </div>
        </div>
    );
}
