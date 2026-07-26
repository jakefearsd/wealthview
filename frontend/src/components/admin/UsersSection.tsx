import { useState } from 'react';
import { useAuth } from '../../context/AuthContext';
import { getAllUsers, resetPassword, setUserActive } from '../../api/adminUsers';
import { listUsers, updateUserRole, deleteUser } from '../../api/tenant';
import type { AdminUser } from '../../api/adminUsers';
import type { TenantUser } from '../../types/tenant';
import { useApiQuery } from '../../hooks/useApiQuery';
import { useApiMutation } from '../../hooks/useApiMutation';
import { cardStyle, tableStyle, thStyle, tdStyle, trHoverStyle } from '../../utils/styles';
import Button from '../Button';

export default function UsersSection() {
    const { role } = useAuth();
    const isSuperAdmin = role === 'super_admin';

    const { data: adminUsers, loading: adminLoading, refetch: refetchAdmin } = useApiQuery<AdminUser[]>(getAllUsers);
    const { data: tenantUsers, loading: tenantLoading, refetch: refetchTenant } = useApiQuery<TenantUser[]>(listUsers);

    const [resetModal, setResetModal] = useState<{ userId: string; email: string } | null>(null);
    const [newPassword, setNewPassword] = useState('');

    const users = isSuperAdmin ? adminUsers : null;
    const loading = isSuperAdmin ? adminLoading : tenantLoading;

    function refetchUsers() {
        if (isSuperAdmin) refetchAdmin(); else refetchTenant();
    }

    const resetPasswordMutation = useApiMutation(
        (input: { userId: string; email: string; password: string }) => resetPassword(input.userId, input.password),
        {
            successMessage: (_result, input) => `Password reset for ${input.email}`,
            onSuccess: () => {
                setResetModal(null);
                setNewPassword('');
            },
        },
    );
    const resetting = resetPasswordMutation.loading;

    function handleResetPassword() {
        if (!resetModal || !newPassword.trim()) return;
        void resetPasswordMutation.mutate({ userId: resetModal.userId, email: resetModal.email, password: newPassword.trim() });
    }

    const toggleActiveMutation = useApiMutation(
        (input: { userId: string; email: string; nextActive: boolean }) => setUserActive(input.userId, input.nextActive),
        {
            successMessage: (_result, input) => `${input.email} ${input.nextActive ? 'activated' : 'deactivated'}`,
            onSuccess: () => refetchAdmin(),
        },
    );

    function handleToggleActive(userId: string, email: string, currentActive: boolean) {
        void toggleActiveMutation.mutate({ userId, email, nextActive: !currentActive });
    }

    const roleChangeMutation = useApiMutation(
        (input: { userId: string; role: string }) => updateUserRole(input.userId, input.role),
        {
            successMessage: 'Role updated',
            onSuccess: () => refetchUsers(),
        },
    );

    function handleRoleChange(userId: string, newRole: string) {
        void roleChangeMutation.mutate({ userId, role: newRole });
    }

    const deleteUserMutation = useApiMutation(
        (userId: string) => deleteUser(userId),
        {
            successMessage: 'User removed',
            onSuccess: () => refetchUsers(),
        },
    );

    function handleDelete(userId: string) {
        if (!confirm('Remove this user? This cannot be undone.')) return;
        void deleteUserMutation.mutate(userId);
    }

    if (loading) return <div>Loading...</div>;

    return (
        <div>
            <h2 style={{ marginBottom: '1.5rem' }}>Users</h2>

            <div style={cardStyle}>
                <table style={tableStyle}>
                    <thead>
                        <tr>
                            <th style={thStyle}>Email</th>
                            <th style={thStyle}>Role</th>
                            {isSuperAdmin && <th style={thStyle}>Tenant</th>}
                            <th style={thStyle}>Joined</th>
                            {isSuperAdmin && <th style={{ ...thStyle, textAlign: 'center' }}>Status</th>}
                            <th style={{ ...thStyle, textAlign: 'center' }}>Actions</th>
                        </tr>
                    </thead>
                    <tbody>
                        {isSuperAdmin && users?.map((user) => (
                            <tr key={user.id} style={trHoverStyle}>
                                <td style={tdStyle}>{user.email}</td>
                                <td style={tdStyle}>
                                    <select
                                        value={user.role}
                                        onChange={(e) => handleRoleChange(user.id, e.target.value)}
                                        style={{ padding: '0.3rem' }}
                                    >
                                        <option value="super_admin">Super Admin</option>
                                        <option value="admin">Admin</option>
                                        <option value="member">Member</option>
                                        <option value="viewer">Viewer</option>
                                    </select>
                                </td>
                                <td style={{ ...tdStyle, fontSize: '0.85rem', color: '#666' }}>
                                    {user.tenant_name}
                                </td>
                                <td style={{ ...tdStyle, fontSize: '0.85rem', color: '#666' }}>
                                    {new Date(user.created_at).toLocaleDateString()}
                                </td>
                                <td style={{ ...tdStyle, textAlign: 'center' }}>
                                    <span style={{
                                        padding: '0.15rem 0.4rem',
                                        borderRadius: '4px',
                                        fontSize: '0.8rem',
                                        background: user.is_active ? '#e8f5e9' : '#ffebee',
                                        color: user.is_active ? '#2e7d32' : '#c62828',
                                    }}>
                                        {user.is_active ? 'Active' : 'Disabled'}
                                    </span>
                                </td>
                                <td style={{ ...tdStyle, textAlign: 'center' }}>
                                    <div style={{ display: 'flex', gap: '0.5rem', justifyContent: 'center' }}>
                                        <button
                                            onClick={() => setResetModal({ userId: user.id, email: user.email })}
                                            style={{ background: 'none', border: 'none', color: '#1976d2', cursor: 'pointer', fontSize: '0.85rem' }}
                                        >
                                            Reset PW
                                        </button>
                                        <button
                                            onClick={() => handleToggleActive(user.id, user.email, user.is_active)}
                                            style={{ background: 'none', border: 'none', color: user.is_active ? '#d32f2f' : '#2e7d32', cursor: 'pointer', fontSize: '0.85rem' }}
                                        >
                                            {user.is_active ? 'Deactivate' : 'Activate'}
                                        </button>
                                        <button
                                            onClick={() => handleDelete(user.id)}
                                            style={{ background: 'none', border: 'none', color: '#d32f2f', cursor: 'pointer', fontSize: '0.85rem' }}
                                        >
                                            Delete
                                        </button>
                                    </div>
                                </td>
                            </tr>
                        ))}
                        {!isSuperAdmin && tenantUsers?.map((user) => (
                            <tr key={user.id} style={trHoverStyle}>
                                <td style={tdStyle}>{user.email}</td>
                                <td style={tdStyle}>
                                    <select
                                        value={user.role}
                                        onChange={(e) => handleRoleChange(user.id, e.target.value)}
                                        style={{ padding: '0.3rem' }}
                                    >
                                        <option value="admin">Admin</option>
                                        <option value="member">Member</option>
                                        <option value="viewer">Viewer</option>
                                    </select>
                                </td>
                                <td style={{ ...tdStyle, fontSize: '0.85rem', color: '#666' }}>
                                    {new Date(user.created_at).toLocaleDateString()}
                                </td>
                                <td style={{ ...tdStyle, textAlign: 'center' }}>
                                    <button
                                        onClick={() => handleDelete(user.id)}
                                        style={{ background: 'none', border: 'none', color: '#d32f2f', cursor: 'pointer' }}
                                    >
                                        Remove
                                    </button>
                                </td>
                            </tr>
                        ))}
                        {((isSuperAdmin && (!users || users.length === 0)) ||
                          (!isSuperAdmin && (!tenantUsers || tenantUsers.length === 0))) && (
                            <tr>
                                <td colSpan={6} style={{ padding: '1rem', color: '#999', textAlign: 'center' }}>
                                    No users found
                                </td>
                            </tr>
                        )}
                    </tbody>
                </table>
            </div>

            {/* Reset Password Modal */}
            {resetModal && (
                <div style={{
                    position: 'fixed',
                    top: 0, left: 0, right: 0, bottom: 0,
                    background: 'rgba(0,0,0,0.5)',
                    display: 'flex',
                    alignItems: 'center',
                    justifyContent: 'center',
                    zIndex: 1000,
                }}>
                    <div style={{ ...cardStyle, width: '400px' }}>
                        <h3 style={{ marginBottom: '1rem' }}>Reset Password</h3>
                        <p style={{ marginBottom: '1rem', color: '#666' }}>
                            Set a new password for <strong>{resetModal.email}</strong>
                        </p>
                        <input
                            type="password"
                            value={newPassword}
                            onChange={(e) => setNewPassword(e.target.value)}
                            placeholder="New password"
                            style={{ width: '100%', padding: '0.5rem', border: '1px solid #ccc', borderRadius: '4px', marginBottom: '1rem', boxSizing: 'border-box' }}
                        />
                        <div style={{ display: 'flex', gap: '0.5rem', justifyContent: 'flex-end' }}>
                            <Button
                                onClick={() => { setResetModal(null); setNewPassword(''); }}
                                variant="secondary"
                                style={{ background: '#fff', color: '#333', border: '1px solid #ccc' }}
                            >
                                Cancel
                            </Button>
                            <Button
                                onClick={handleResetPassword}
                                disabled={resetting || !newPassword.trim()}
                            >
                                {resetting ? 'Resetting...' : 'Reset Password'}
                            </Button>
                        </div>
                    </div>
                </div>
            )}
        </div>
    );
}
