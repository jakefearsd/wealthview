import { useState } from 'react';
import { listTenantDetails, createTenant, setTenantActive } from '../api/admin';
import { useApiQuery } from '../hooks/useApiQuery';
import { cardStyle } from '../utils/styles';
import toast from 'react-hot-toast';

export default function AdminPage() {
    const { data: tenants, loading, refetch } = useApiQuery(listTenantDetails);
    const [newName, setNewName] = useState('');
    const [creating, setCreating] = useState(false);

    async function handleCreate() {
        if (!newName.trim()) return;
        setCreating(true);
        try {
            await createTenant(newName.trim());
            toast.success('Tenant created');
            setNewName('');
            refetch();
        } catch {
            toast.error('Failed to create tenant');
        } finally {
            setCreating(false);
        }
    }

    async function handleToggleActive(id: string, currentActive: boolean) {
        try {
            await setTenantActive(id, !currentActive);
            toast.success(currentActive ? 'Tenant disabled' : 'Tenant enabled');
            refetch();
        } catch {
            toast.error('Failed to update tenant');
        }
    }

    if (loading) return <div>Loading...</div>;

    return (
        <div>
            <h2 style={{ marginBottom: '1.5rem' }}>Admin Panel</h2>

            <div style={{ ...cardStyle, marginBottom: '2rem' }}>
                <h3 style={{ marginBottom: '1rem' }}>Create Tenant</h3>
                <div style={{ display: 'flex', gap: '0.5rem' }}>
                    <input
                        type="text"
                        value={newName}
                        onChange={(e) => setNewName(e.target.value)}
                        placeholder="Tenant name"
                        style={{ padding: '0.5rem', border: '1px solid #ccc', borderRadius: '4px', flex: 1 }}
                    />
                    <button
                        onClick={handleCreate}
                        disabled={creating}
                        style={{ padding: '0.5rem 1rem', background: '#1976d2', color: '#fff', border: 'none', borderRadius: '4px', cursor: 'pointer' }}
                    >
                        {creating ? 'Creating...' : 'Create'}
                    </button>
                </div>
            </div>

            <div style={cardStyle}>
                <h3 style={{ marginBottom: '1rem' }}>Tenants</h3>
                <table style={{ width: '100%', borderCollapse: 'collapse' }}>
                    <thead>
                        <tr style={{ borderBottom: '2px solid #e0e0e0' }}>
                            <th style={{ textAlign: 'left', padding: '0.5rem' }}>Name</th>
                            <th style={{ textAlign: 'right', padding: '0.5rem' }}>Users</th>
                            <th style={{ textAlign: 'right', padding: '0.5rem' }}>Accounts</th>
                            <th style={{ textAlign: 'center', padding: '0.5rem' }}>Status</th>
                            <th style={{ textAlign: 'left', padding: '0.5rem' }}>Created</th>
                            <th style={{ padding: '0.5rem' }}></th>
                        </tr>
                    </thead>
                    <tbody>
                        {tenants?.map((t) => (
                            <tr key={t.id} style={{ borderBottom: '1px solid #f0f0f0' }}>
                                <td style={{ padding: '0.5rem' }}>{t.name}</td>
                                <td style={{ padding: '0.5rem', textAlign: 'right' }}>{t.user_count}</td>
                                <td style={{ padding: '0.5rem', textAlign: 'right' }}>{t.account_count}</td>
                                <td style={{ padding: '0.5rem', textAlign: 'center' }}>
                                    <span style={{
                                        padding: '0.2rem 0.5rem',
                                        borderRadius: '4px',
                                        fontSize: '0.8rem',
                                        background: t.is_active ? '#e8f5e9' : '#ffebee',
                                        color: t.is_active ? '#2e7d32' : '#c62828',
                                    }}>
                                        {t.is_active ? 'Active' : 'Disabled'}
                                    </span>
                                </td>
                                <td style={{ padding: '0.5rem', fontSize: '0.85rem', color: '#666' }}>
                                    {new Date(t.created_at).toLocaleDateString()}
                                </td>
                                <td style={{ padding: '0.5rem', textAlign: 'center' }}>
                                    <button
                                        onClick={() => handleToggleActive(t.id, t.is_active)}
                                        style={{
                                            background: 'none',
                                            border: 'none',
                                            cursor: 'pointer',
                                            color: t.is_active ? '#d32f2f' : '#2e7d32',
                                        }}
                                    >
                                        {t.is_active ? 'Disable' : 'Enable'}
                                    </button>
                                </td>
                            </tr>
                        ))}
                        {tenants?.length === 0 && (
                            <tr><td colSpan={6} style={{ padding: '1rem', color: '#999', textAlign: 'center' }}>No tenants</td></tr>
                        )}
                    </tbody>
                </table>
            </div>
        </div>
    );
}
