import { useState } from 'react';
import toast from 'react-hot-toast';
import { listTenantDetails, createTenant, setTenantActive, generateTenantInviteCode } from '../../api/admin';
import { useApiQuery } from '../../hooks/useApiQuery';
import { useApiMutation } from '../../hooks/useApiMutation';
import { cardStyle, tableStyle, thStyle, tdStyle, trHoverStyle } from '../../utils/styles';
import { formatDate } from '../../utils/format';
import Button from '../Button';
import ErrorState from '../ErrorState';
import LinkButton from '../LinkButton';
import VisuallyHidden from '../VisuallyHidden';
import type { InviteCode } from '../../types/tenant';

export default function TenantsSection() {
    const { data: tenants, loading, error, refetch } = useApiQuery(listTenantDetails);
    const [newName, setNewName] = useState('');
    const [generated, setGenerated] = useState<{ tenantName: string; invite: InviteCode } | null>(null);

    const createMutation = useApiMutation(
        (name: string) => createTenant(name),
        {
            successMessage: 'Tenant created',
            onSuccess: () => {
                setNewName('');
                refetch();
            },
        },
    );
    const creating = createMutation.loading;

    function handleCreate() {
        if (!newName.trim()) return;
        void createMutation.mutate(newName.trim());
    }

    const toggleActiveMutation = useApiMutation(
        (input: { id: string; nextActive: boolean }) => setTenantActive(input.id, input.nextActive),
        {
            successMessage: (_result, input) => (input.nextActive ? 'Tenant enabled' : 'Tenant disabled'),
            onSuccess: () => refetch(),
        },
    );

    function handleToggleActive(id: string, currentActive: boolean) {
        void toggleActiveMutation.mutate({ id, nextActive: !currentActive });
    }

    const inviteMutation = useApiMutation(
        (input: { id: string; name: string }) => generateTenantInviteCode(input.id),
        {
            successMessage: 'Invite code created',
            onSuccess: (invite, input) => setGenerated({ tenantName: input.name, invite }),
        },
    );

    function handleCreateInvite(id: string, name: string) {
        void inviteMutation.mutate({ id, name });
    }

    function handleCopy(code: string) {
        navigator.clipboard.writeText(code).then(
            () => toast.success('Code copied to clipboard'),
            () => toast.error('Failed to copy'),
        );
    }

    if (loading) return <div>Loading...</div>;
    if (error && !tenants) return <ErrorState message={error} onRetry={refetch} />;

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

            {generated && (
                <div role="status" style={{ ...cardStyle, marginBottom: '2rem' }}>
                    <h3 style={{ marginBottom: '0.5rem' }}>Invite code for {generated.tenantName}</h3>
                    <div style={{ display: 'flex', gap: '1rem', alignItems: 'center', flexWrap: 'wrap' }}>
                        <code style={{ fontFamily: 'monospace', fontSize: '1rem' }}>{generated.invite.code}</code>
                        <span style={{ fontSize: '0.85rem', color: '#666' }}>
                            Expires {formatDate(generated.invite.expires_at)}
                        </span>
                        <LinkButton onClick={() => handleCopy(generated.invite.code)} aria-label="Copy invite code">
                            Copy
                        </LinkButton>
                    </div>
                    <p style={{ fontSize: '0.85rem', color: '#666', marginTop: '0.5rem' }}>
                        The first user to register with a code in a tenant with no users becomes that tenant&apos;s admin.
                    </p>
                </div>
            )}

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
                            <th style={thStyle}><VisuallyHidden>Actions</VisuallyHidden></th>
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
                                    {formatDate(t.created_at)}
                                </td>
                                <td style={{ ...tdStyle, textAlign: 'center' }}>
                                    <LinkButton
                                        onClick={() => handleCreateInvite(t.id, t.name)}
                                        disabled={!t.is_active || inviteMutation.loading}
                                        aria-label={`Create invite code for ${t.name}`}
                                        title={t.is_active ? undefined : 'Enable the tenant to create invite codes'}
                                        style={{ marginRight: '0.75rem' }}
                                    >
                                        Create invite code
                                    </LinkButton>
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
                            <tr><td colSpan={6} style={{ padding: '1rem', color: '#666', textAlign: 'center' }}>No tenants</td></tr>
                        )}
                    </tbody>
                </table>
            </div>
        </div>
    );
}
