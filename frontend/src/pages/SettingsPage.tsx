import { useState, useEffect } from 'react';
import { generateInviteCode, listInviteCodes, listUsers, updateUserRole, deleteUser } from '../api/tenant';
import { getNotificationPreferences, updateNotificationPreferences } from '../api/notifications';
import type { NotificationPreference } from '../api/notifications';
import { useApiQuery } from '../hooks/useApiQuery';
import { cardStyle } from '../utils/styles';
import toast from 'react-hot-toast';

const NOTIFICATION_LABELS: Record<string, string> = {
    LARGE_TRANSACTION: 'Large transactions',
    IMPORT_COMPLETE: 'Import completed',
    IMPORT_FAILED: 'Import failed',
};

export default function SettingsPage() {
    const { data: inviteCodes, refetch: refetchCodes } = useApiQuery(listInviteCodes);
    const { data: users, refetch: refetchUsers } = useApiQuery(listUsers);
    const [generating, setGenerating] = useState(false);
    const [notifPrefs, setNotifPrefs] = useState<NotificationPreference[]>([]);

    useEffect(() => {
        getNotificationPreferences().then(setNotifPrefs).catch(() => {});
    }, []);

    async function handleNotifToggle(type: string, enabled: boolean) {
        const updated = notifPrefs.map((p) =>
            p.notification_type === type ? { ...p, enabled } : p
        );
        setNotifPrefs(updated);
        try {
            await updateNotificationPreferences(updated);
        } catch {
            toast.error('Failed to update notification preferences');
            getNotificationPreferences().then(setNotifPrefs).catch(() => {});
        }
    }

    async function handleGenerateCode() {
        setGenerating(true);
        try {
            await generateInviteCode();
            toast.success('Invite code generated');
            refetchCodes();
        } catch {
            toast.error('Failed to generate invite code');
        } finally {
            setGenerating(false);
        }
    }

    async function handleRoleChange(userId: string, newRole: string) {
        try {
            await updateUserRole(userId, newRole);
            toast.success('Role updated');
            refetchUsers();
        } catch {
            toast.error('Failed to update role');
        }
    }

    async function handleDeleteUser(userId: string) {
        if (!confirm('Remove this user?')) return;
        try {
            await deleteUser(userId);
            toast.success('User removed');
            refetchUsers();
        } catch {
            toast.error('Failed to remove user');
        }
    }

    return (
        <div>
            <h2 style={{ marginBottom: '1.5rem' }}>Settings</h2>

            <div style={{ ...cardStyle, marginBottom: '2rem' }}>
                <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '1rem' }}>
                    <h3>Invite Codes</h3>
                    <button onClick={handleGenerateCode} disabled={generating} style={{ padding: '0.5rem 1rem', background: '#1976d2', color: '#fff', border: 'none', borderRadius: '4px', cursor: 'pointer' }}>
                        {generating ? 'Generating...' : 'Generate Code'}
                    </button>
                </div>
                <table style={{ width: '100%', borderCollapse: 'collapse' }}>
                    <thead>
                        <tr style={{ borderBottom: '2px solid #e0e0e0' }}>
                            <th style={{ textAlign: 'left', padding: '0.5rem' }}>Code</th>
                            <th style={{ textAlign: 'left', padding: '0.5rem' }}>Expires</th>
                            <th style={{ textAlign: 'left', padding: '0.5rem' }}>Status</th>
                        </tr>
                    </thead>
                    <tbody>
                        {inviteCodes?.map((code) => (
                            <tr key={code.id} style={{ borderBottom: '1px solid #f0f0f0' }}>
                                <td style={{ padding: '0.5rem', fontFamily: 'monospace' }}>{code.code}</td>
                                <td style={{ padding: '0.5rem' }}>{new Date(code.expires_at).toLocaleDateString()}</td>
                                <td style={{ padding: '0.5rem' }}>
                                    <span style={{ color: code.consumed ? '#d32f2f' : '#2e7d32' }}>
                                        {code.consumed ? 'Used' : 'Active'}
                                    </span>
                                </td>
                            </tr>
                        ))}
                    </tbody>
                </table>
            </div>

            <div style={{ ...cardStyle, marginBottom: '2rem' }}>
                <h3 style={{ marginBottom: '1rem' }}>Notification Preferences</h3>
                {notifPrefs.map((pref) => (
                    <label key={pref.notification_type} style={{ display: 'flex', alignItems: 'center', gap: '0.5rem', marginBottom: '0.5rem' }}>
                        <input
                            type="checkbox"
                            checked={pref.enabled}
                            onChange={(e) => handleNotifToggle(pref.notification_type, e.target.checked)}
                        />
                        {NOTIFICATION_LABELS[pref.notification_type] ?? pref.notification_type}
                    </label>
                ))}
                {notifPrefs.length === 0 && <div style={{ color: '#999' }}>Loading...</div>}
            </div>

            <div style={cardStyle}>
                <h3 style={{ marginBottom: '1rem' }}>Users</h3>
                <table style={{ width: '100%', borderCollapse: 'collapse' }}>
                    <thead>
                        <tr style={{ borderBottom: '2px solid #e0e0e0' }}>
                            <th style={{ textAlign: 'left', padding: '0.5rem' }}>Email</th>
                            <th style={{ textAlign: 'left', padding: '0.5rem' }}>Role</th>
                            <th style={{ textAlign: 'left', padding: '0.5rem' }}>Joined</th>
                            <th style={{ padding: '0.5rem' }}>Actions</th>
                        </tr>
                    </thead>
                    <tbody>
                        {users?.map((user) => (
                            <tr key={user.id} style={{ borderBottom: '1px solid #f0f0f0' }}>
                                <td style={{ padding: '0.5rem' }}>{user.email}</td>
                                <td style={{ padding: '0.5rem' }}>
                                    <select value={user.role} onChange={(e) => handleRoleChange(user.id, e.target.value)} style={{ padding: '0.3rem' }}>
                                        <option value="admin">Admin</option>
                                        <option value="member">Member</option>
                                        <option value="viewer">Viewer</option>
                                    </select>
                                </td>
                                <td style={{ padding: '0.5rem' }}>{new Date(user.created_at).toLocaleDateString()}</td>
                                <td style={{ padding: '0.5rem', textAlign: 'center' }}>
                                    <button onClick={() => handleDeleteUser(user.id)} style={{ background: 'none', border: 'none', color: '#d32f2f', cursor: 'pointer' }}>Remove</button>
                                </td>
                            </tr>
                        ))}
                    </tbody>
                </table>
            </div>
        </div>
    );
}
