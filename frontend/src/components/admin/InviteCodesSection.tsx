import { useState } from 'react';
import { listInviteCodes, generateInviteCodeWithExpiry, revokeInviteCode, deleteUsedCodes } from '../../api/tenant';
import { useApiQuery } from '../../hooks/useApiQuery';
import { useApiMutation } from '../../hooks/useApiMutation';
import { cardStyle, tableStyle, thStyle, tdStyle, trHoverStyle } from '../../utils/styles';
import Button from '../Button';
import LinkButton from '../LinkButton';
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

    const generateMutation = useApiMutation(
        (days: number) => generateInviteCodeWithExpiry(days),
        {
            successMessage: 'Invite code generated',
            onSuccess: () => refetch(),
        },
    );
    const generating = generateMutation.loading;

    function handleGenerate() {
        void generateMutation.mutate(expiryDays);
    }

    const revokeMutation = useApiMutation(
        (id: string) => revokeInviteCode(id),
        {
            successMessage: 'Invite code revoked',
            onSuccess: () => refetch(),
        },
    );

    function handleRevoke(id: string) {
        void revokeMutation.mutate(id);
    }

    const deleteUsedMutation = useApiMutation(
        () => deleteUsedCodes(),
        {
            successMessage: (result) => `Deleted ${result.deleted} used codes`,
            onSuccess: () => refetch(),
        },
    );
    const deleting = deleteUsedMutation.loading;

    function handleDeleteUsed() {
        if (!confirm('Delete all used invite codes? This cannot be undone.')) return;
        void deleteUsedMutation.mutate(undefined);
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
                    <Button onClick={handleGenerate} disabled={generating}>
                        {generating ? 'Generating...' : 'Generate Code'}
                    </Button>
                    {hasUsedCodes && (
                        <Button onClick={handleDeleteUsed} disabled={deleting} variant="danger" style={{ marginLeft: 'auto' }}>
                            {deleting ? 'Deleting...' : 'Delete Used Codes'}
                        </Button>
                    )}
                </div>
            </div>

            <div style={cardStyle}>
                <table style={tableStyle}>
                    <thead>
                        <tr>
                            <th style={thStyle}>Code</th>
                            <th style={thStyle}>Created By</th>
                            <th style={thStyle}>Created</th>
                            <th style={thStyle}>Expires</th>
                            <th style={{ ...thStyle, textAlign: 'center' }}>Status</th>
                            <th style={thStyle}>Used By</th>
                            <th style={{ ...thStyle, textAlign: 'center' }}>Actions</th>
                        </tr>
                    </thead>
                    <tbody>
                        {codes?.map((code) => {
                            const status = getStatus(code);
                            const statusStyle = getStatusColor(status);
                            return (
                                <tr key={code.id} style={trHoverStyle}>
                                    <td style={{ ...tdStyle, fontFamily: 'monospace', fontSize: '0.9rem' }}>
                                        {code.code}
                                    </td>
                                    <td style={{ ...tdStyle, fontSize: '0.85rem', color: '#666' }}>
                                        {code.created_by_email ?? '-'}
                                    </td>
                                    <td style={{ ...tdStyle, fontSize: '0.85rem', color: '#666' }}>
                                        {new Date(code.created_at).toLocaleDateString()}
                                    </td>
                                    <td style={{ ...tdStyle, fontSize: '0.85rem', color: '#666' }}>
                                        {new Date(code.expires_at).toLocaleDateString()}
                                    </td>
                                    <td style={{ ...tdStyle, textAlign: 'center' }}>
                                        <span style={{
                                            padding: '0.15rem 0.4rem',
                                            borderRadius: '4px',
                                            fontSize: '0.8rem',
                                            ...statusStyle,
                                        }}>
                                            {status}
                                        </span>
                                    </td>
                                    <td style={{ ...tdStyle, fontSize: '0.85rem', color: '#666' }}>
                                        {code.used_by_email ?? '-'}
                                    </td>
                                    <td style={{ ...tdStyle, textAlign: 'center' }}>
                                        <div style={{ display: 'flex', gap: '0.5rem', justifyContent: 'center' }}>
                                            <LinkButton onClick={() => handleCopy(code.code)}>
                                                Copy
                                            </LinkButton>
                                            {status === 'Active' && (
                                                <LinkButton variant="danger" onClick={() => handleRevoke(code.id)}>
                                                    Revoke
                                                </LinkButton>
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
