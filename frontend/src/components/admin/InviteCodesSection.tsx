import { useState } from 'react';
import { listInviteCodes, generateInviteCodeWithExpiry, revokeInviteCode, deleteUsedCodes } from '../../api/tenant';
import { useApiQuery } from '../../hooks/useApiQuery';
import { cardStyle } from '../../utils/styles';
import toast from 'react-hot-toast';

const EXPIRY_OPTIONS = [
    { label: '1 day', value: 1 },
    { label: '7 days', value: 7 },
    { label: '30 days', value: 30 },
    { label: '90 days', value: 90 },
];

function getStatus(code: { consumed: boolean; is_revoked: boolean; expires_at: string }): string {
    if (code.is_revoked) return 'Revoked';
    if (code.consumed) return 'Used';
    if (new Date(code.expires_at) < new Date()) return 'Expired';
    return 'Active';
}

function getStatusColor(status: string): { background: string; color: string } {
    switch (status) {
        case 'Active': return { background: '#e8f5e9', color: '#2e7d32' };
        case 'Used': return { background: '#e3f2fd', color: '#1565c0' };
        case 'Expired': return { background: '#fff3e0', color: '#e65100' };
        case 'Revoked': return { background: '#ffebee', color: '#c62828' };
        default: return { background: '#f5f5f5', color: '#666' };
    }
}

export default function InviteCodesSection() {
    const { data: codes, refetch } = useApiQuery(listInviteCodes);
    const [expiryDays, setExpiryDays] = useState(7);
    const [generating, setGenerating] = useState(false);
    const [deleting, setDeleting] = useState(false);

    async function handleGenerate() {
        setGenerating(true);
        try {
            await generateInviteCodeWithExpiry(expiryDays);
            toast.success('Invite code generated');
            refetch();
        } catch {
            toast.error('Failed to generate invite code');
        } finally {
            setGenerating(false);
        }
    }

    async function handleRevoke(id: string) {
        try {
            await revokeInviteCode(id);
            toast.success('Invite code revoked');
            refetch();
        } catch {
            toast.error('Failed to revoke invite code');
        }
    }

    async function handleDeleteUsed() {
        if (!confirm('Delete all used invite codes? This cannot be undone.')) return;
        setDeleting(true);
        try {
            const result = await deleteUsedCodes();
            toast.success(`Deleted ${result.deleted} used codes`);
            refetch();
        } catch {
            toast.error('Failed to delete used codes');
        } finally {
            setDeleting(false);
        }
    }

    function handleCopy(code: string) {
        navigator.clipboard.writeText(code).then(
            () => toast.success('Code copied to clipboard'),
            () => toast.error('Failed to copy')
        );
    }

    const hasUsedCodes = codes?.some((c) => c.consumed) ?? false;

    return (
        <div>
            <h2 style={{ marginBottom: '1.5rem' }}>Invite Codes</h2>

            <div style={{ ...cardStyle, marginBottom: '1.5rem' }}>
                <div style={{ display: 'flex', gap: '0.5rem', alignItems: 'center', flexWrap: 'wrap' }}>
                    <label style={{ fontSize: '0.9rem' }}>Expires in:</label>
                    <select
                        value={expiryDays}
                        onChange={(e) => setExpiryDays(Number(e.target.value))}
                        style={{ padding: '0.4rem', border: '1px solid #ccc', borderRadius: '4px' }}
                    >
                        {EXPIRY_OPTIONS.map((opt) => (
                            <option key={opt.value} value={opt.value}>{opt.label}</option>
                        ))}
                    </select>
                    <button
                        onClick={handleGenerate}
                        disabled={generating}
                        style={{ padding: '0.5rem 1rem', background: '#1976d2', color: '#fff', border: 'none', borderRadius: '4px', cursor: 'pointer' }}
                    >
                        {generating ? 'Generating...' : 'Generate Code'}
                    </button>
                    {hasUsedCodes && (
                        <button
                            onClick={handleDeleteUsed}
                            disabled={deleting}
                            style={{ padding: '0.5rem 1rem', background: '#d32f2f', color: '#fff', border: 'none', borderRadius: '4px', cursor: 'pointer', marginLeft: 'auto' }}
                        >
                            {deleting ? 'Deleting...' : 'Delete Used Codes'}
                        </button>
                    )}
                </div>
            </div>

            <div style={cardStyle}>
                <table style={{ width: '100%', borderCollapse: 'collapse' }}>
                    <thead>
                        <tr style={{ borderBottom: '2px solid #e0e0e0' }}>
                            <th style={{ textAlign: 'left', padding: '0.5rem' }}>Code</th>
                            <th style={{ textAlign: 'left', padding: '0.5rem' }}>Created By</th>
                            <th style={{ textAlign: 'left', padding: '0.5rem' }}>Created</th>
                            <th style={{ textAlign: 'left', padding: '0.5rem' }}>Expires</th>
                            <th style={{ textAlign: 'center', padding: '0.5rem' }}>Status</th>
                            <th style={{ textAlign: 'left', padding: '0.5rem' }}>Used By</th>
                            <th style={{ textAlign: 'center', padding: '0.5rem' }}>Actions</th>
                        </tr>
                    </thead>
                    <tbody>
                        {codes?.map((code) => {
                            const status = getStatus(code);
                            const statusStyle = getStatusColor(status);
                            return (
                                <tr key={code.id} style={{ borderBottom: '1px solid #f0f0f0' }}>
                                    <td style={{ padding: '0.5rem', fontFamily: 'monospace', fontSize: '0.9rem' }}>
                                        {code.code}
                                    </td>
                                    <td style={{ padding: '0.5rem', fontSize: '0.85rem', color: '#666' }}>
                                        {code.created_by_email ?? '-'}
                                    </td>
                                    <td style={{ padding: '0.5rem', fontSize: '0.85rem', color: '#666' }}>
                                        {new Date(code.created_at).toLocaleDateString()}
                                    </td>
                                    <td style={{ padding: '0.5rem', fontSize: '0.85rem', color: '#666' }}>
                                        {new Date(code.expires_at).toLocaleDateString()}
                                    </td>
                                    <td style={{ padding: '0.5rem', textAlign: 'center' }}>
                                        <span style={{
                                            padding: '0.15rem 0.4rem',
                                            borderRadius: '4px',
                                            fontSize: '0.8rem',
                                            ...statusStyle,
                                        }}>
                                            {status}
                                        </span>
                                    </td>
                                    <td style={{ padding: '0.5rem', fontSize: '0.85rem', color: '#666' }}>
                                        {code.used_by_email ?? '-'}
                                    </td>
                                    <td style={{ padding: '0.5rem', textAlign: 'center' }}>
                                        <div style={{ display: 'flex', gap: '0.5rem', justifyContent: 'center' }}>
                                            <button
                                                onClick={() => handleCopy(code.code)}
                                                style={{ background: 'none', border: 'none', color: '#1976d2', cursor: 'pointer', fontSize: '0.85rem' }}
                                            >
                                                Copy
                                            </button>
                                            {status === 'Active' && (
                                                <button
                                                    onClick={() => handleRevoke(code.id)}
                                                    style={{ background: 'none', border: 'none', color: '#d32f2f', cursor: 'pointer', fontSize: '0.85rem' }}
                                                >
                                                    Revoke
                                                </button>
                                            )}
                                        </div>
                                    </td>
                                </tr>
                            );
                        })}
                        {(!codes || codes.length === 0) && (
                            <tr>
                                <td colSpan={7} style={{ padding: '1rem', color: '#999', textAlign: 'center' }}>
                                    No invite codes
                                </td>
                            </tr>
                        )}
                    </tbody>
                </table>
            </div>
        </div>
    );
}
