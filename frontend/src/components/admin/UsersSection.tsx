import { useState } from 'react';
import { useAuth } from '../../context/AuthContext';
import { getAllUsers, resetPassword, setUserActive } from '../../api/adminUsers';
import { listUsers, updateUserRole, deleteUser } from '../../api/tenant';
import type { AdminUser } from '../../api/adminUsers';
import type { TenantUser } from '../../types/tenant';
import { useApiQuery } from '../../hooks/useApiQuery';
import { useApiMutation } from '../../hooks/useApiMutation';
import { formatDate } from '../../utils/format';
import { cardStyle, tableStyle, thStyle, tdStyle, trHoverStyle } from '../../utils/styles';
import { useModalDialog } from '../../hooks/useModalDialog';
import Button from '../Button';
import ErrorState from '../ErrorState';

const ROLE_LABELS: Record<string, string> = {
    super_admin: 'Super Admin',
    admin: 'Admin',
    member: 'Member',
    viewer: 'Viewer',
};

/** Roles the role-change endpoint accepts. A super admin's role is never editable here. */
const ASSIGNABLE_ROLES = ['admin', 'member', 'viewer'] as const;

const NO_USERS: AdminUser[] = [];

function RoleSelect({ email, value, onChange }: { email: string; value: string; onChange: (role: string) => void }) {
    return (
        <select
            aria-label={`Role for ${email}`}
            value={value}
            onChange={(e) => onChange(e.target.value)}
            style={{ padding: '0.3rem' }}
        >
            {ASSIGNABLE_ROLES.map((r) => <option key={r} value={r}>{ROLE_LABELS[r]}</option>)}
        </select>
    );
}

export default function UsersSection() {
    const { role, tenantId } = useAuth();
    const isSuperAdmin = role === 'super_admin';

    // Each role has exactly one list it may read (the other endpoint 403s), so the unused query resolves empty.
    const { data: adminUsers, loading: adminLoading, error: adminError, refetch: refetchAdmin } = useApiQuery<AdminUser[]>(
        () => (isSuperAdmin ? getAllUsers() : Promise.resolve(NO_USERS)), [isSuperAdmin]);
    const { data: tenantUsers, loading: tenantLoading, error: tenantError, refetch: refetchTenant } = useApiQuery<TenantUser[]>(
        () => (isSuperAdmin ? Promise.resolve([]) : listUsers()), [isSuperAdmin]);

    const [resetModal, setResetModal] = useState<{ userId: string; email: string } | null>(null);
    const [newPassword, setNewPassword] = useState('');

    const users = isSuperAdmin ? adminUsers : null;
    const loading = isSuperAdmin ? adminLoading : tenantLoading;
    const error = isSuperAdmin ? adminError : tenantError;

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
        if (!resetModal || !newPassword) return;
        // Sent exactly as typed: trimming would silently change a password that has leading or trailing spaces.
        void resetPasswordMutation.mutate({ userId: resetModal.userId, email: resetModal.email, password: newPassword });
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

    function closeResetModal() {
        setResetModal(null);
        setNewPassword('');
    }

    if (loading) return <div>Loading...</div>;
    // A failed refetch keeps the list already on screen; only a failed first load shows the error card.
    if (error && !(isSuperAdmin ? adminUsers : tenantUsers)) return <ErrorState message={error} onRetry={refetchUsers} />;

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
                                    {/* Role changes and deletes go through tenant-scoped endpoints, which cannot reach other
                                        tenants' users, and a super admin's role is not editable at all. */}
                                    {user.role === 'super_admin' || user.tenant_id !== tenantId
                                        ? (ROLE_LABELS[user.role] ?? user.role)
                                        : <RoleSelect email={user.email} value={user.role} onChange={(r) => handleRoleChange(user.id, r)} />}
                                </td>
                                <td style={{ ...tdStyle, fontSize: '0.85rem', color: '#666' }}>
                                    {user.tenant_name}
                                </td>
                                <td style={{ ...tdStyle, fontSize: '0.85rem', color: '#666' }}>
                                    {formatDate(user.created_at)}
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
                                        {user.tenant_id === tenantId && (
                                            <button
                                                onClick={() => handleDelete(user.id)}
                                                style={{ background: 'none', border: 'none', color: '#d32f2f', cursor: 'pointer', fontSize: '0.85rem' }}
                                            >
                                                Delete
                                            </button>
                                        )}
                                    </div>
                                </td>
                            </tr>
                        ))}
                        {!isSuperAdmin && tenantUsers?.map((user) => (
                            <tr key={user.id} style={trHoverStyle}>
                                <td style={tdStyle}>{user.email}</td>
                                <td style={tdStyle}>
                                    <RoleSelect email={user.email} value={user.role} onChange={(r) => handleRoleChange(user.id, r)} />
                                </td>
                                <td style={{ ...tdStyle, fontSize: '0.85rem', color: '#666' }}>
                                    {formatDate(user.created_at)}
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
                                <td colSpan={6} style={{ padding: '1rem', color: '#666', textAlign: 'center' }}>
                                    No users found
                                </td>
                            </tr>
                        )}
                    </tbody>
                </table>
            </div>

            {resetModal && (
                <ResetPasswordDialog
                    email={resetModal.email}
                    password={newPassword}
                    resetting={resetting}
                    onPasswordChange={setNewPassword}
                    onSubmit={handleResetPassword}
                    onClose={closeResetModal}
                />
            )}
        </div>
    );
}

interface ResetPasswordDialogProps {
    email: string;
    password: string;
    resetting: boolean;
    onPasswordChange: (value: string) => void;
    onSubmit: () => void;
    onClose: () => void;
}

function ResetPasswordDialog({ email, password, resetting, onPasswordChange, onSubmit, onClose }: ResetPasswordDialogProps) {
    const dialogRef = useModalDialog<HTMLDivElement>(onClose);

    return (
        <div style={{
            position: 'fixed',
            top: 0, left: 0, right: 0, bottom: 0,
            background: 'rgba(0,0,0,0.5)',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            zIndex: 1000,
        }}>
            <div
                ref={dialogRef}
                role="dialog"
                aria-modal="true"
                aria-labelledby="reset-password-title"
                tabIndex={-1}
                style={{ ...cardStyle, width: '400px' }}
            >
                <h3 id="reset-password-title" style={{ marginBottom: '1rem' }}>Reset Password</h3>
                <p style={{ marginBottom: '1rem', color: '#666' }}>
                    Set a new password for <strong>{email}</strong>
                </p>
                <input
                    type="password"
                    aria-label="New password"
                    aria-describedby="reset-password-hint"
                    autoComplete="new-password"
                    value={password}
                    onChange={(e) => onPasswordChange(e.target.value)}
                    placeholder="New password"
                    style={{ width: '100%', padding: '0.5rem', border: '1px solid #ccc', borderRadius: '4px', marginBottom: '0.25rem', boxSizing: 'border-box' }}
                />
                <div id="reset-password-hint" style={{ fontSize: '0.8rem', color: '#666', marginBottom: '1rem' }}>
                    12–64 characters. Common passwords are rejected.
                </div>
                <div style={{ display: 'flex', gap: '0.5rem', justifyContent: 'flex-end' }}>
                    <Button
                        onClick={onClose}
                        variant="secondary"
                        style={{ background: '#fff', color: '#333', border: '1px solid #ccc' }}
                    >
                        Cancel
                    </Button>
                    <Button onClick={onSubmit} disabled={resetting || !password}>
                        {resetting ? 'Resetting...' : 'Reset Password'}
                    </Button>
                </div>
            </div>
        </div>
    );
}
