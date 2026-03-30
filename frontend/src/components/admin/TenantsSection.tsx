import { useState } from 'react';
import { listTenantDetails, createTenant, setTenantActive } from '../../api/admin';
import { useApiQuery } from '../../hooks/useApiQuery';
import { cardStyle, tableStyle, thStyle, tdStyle, trHoverStyle } from '../../utils/styles';
import Button from '../Button';
import toast from 'react-hot-toast';

export default function TenantsSection() {
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
            <h2 style={{ marginBottom: '1.5rem' }}>Tenants</h2>

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
                    <Button onClick={handleCreate} disabled={creating}>
                        {creating ? 'Creating...' : 'Create'}
                    </Button>
                </div>
            </div>

            <div style={cardStyle}>
                <h3 style={{ marginBottom: '1rem' }}>Tenants</h3>
                <table style={tableStyle}>
                    <thead>
                        <tr>
                            <th style={thStyle}>Name</th>
                            <th style={{ ...thStyle, textAlign: 'right' }}>Users</th>
                            <th style={{ ...thStyle, textAlign: 'right' }}>Accounts</th>
                            <th style={{ ...thStyle, textAlign: 'center' }}>Status</th>
                            <th style={thStyle}>Created</th>
                            <th style={thStyle}></th>
                        </tr>
                    </thead>
                    <tbody>
                        {tenants?.map((t) => (
                            <tr key={t.id} style={trHoverStyle}>
                                <td style={tdStyle}>{t.name}</td>
                                <td style={{ ...tdStyle, textAlign: 'right' }}>{t.user_count}</td>
                                <td style={{ ...tdStyle, textAlign: 'right' }}>{t.account_count}</td>
                                <td style={{ ...tdStyle, textAlign: 'center' }}>
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
                                <td style={{ ...tdStyle, fontSize: '0.85rem', color: '#666' }}>
                                    {new Date(t.created_at).toLocaleDateString()}
                                </td>
                                <td style={{ ...tdStyle, textAlign: 'center' }}>
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
