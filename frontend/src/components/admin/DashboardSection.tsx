import { useState } from 'react';
import { getSystemStats, getLoginActivity } from '../../api/adminSystem';
import { syncFinnhub, syncYahoo } from '../../api/adminPrices';
import type { SystemStats, LoginActivity } from '../../api/adminSystem';
import type { YahooSyncResult } from '../../api/adminPrices';
import { useApiQuery } from '../../hooks/useApiQuery';
import { cardStyle } from '../../utils/styles';
import toast from 'react-hot-toast';

const statCardStyle = {
    ...cardStyle,
    textAlign: 'center' as const,
    flex: 1,
    minWidth: '140px',
};

function StatCard({ label, value }: { label: string; value: string | number }) {
    return (
        <div style={statCardStyle}>
            <div style={{ fontSize: '1.6rem', fontWeight: 700, color: '#1565c0' }}>{value}</div>
            <div style={{ fontSize: '0.8rem', color: '#666', marginTop: '0.25rem' }}>{label}</div>
        </div>
    );
}

export default function DashboardSection() {
    const { data: stats, loading: statsLoading } = useApiQuery<SystemStats>(getSystemStats);
    const { data: activity, loading: activityLoading } = useApiQuery<LoginActivity[]>(
        () => getLoginActivity(50)
    );
    const [syncing, setSyncing] = useState<'finnhub' | 'yahoo' | null>(null);

    async function handleSyncFinnhub() {
        setSyncing('finnhub');
        try {
            await syncFinnhub();
            toast.success('Finnhub sync complete');
        } catch {
            toast.error('Finnhub sync failed');
        } finally {
            setSyncing(null);
        }
    }

    async function handleSyncYahoo() {
        setSyncing('yahoo');
        try {
            const result: YahooSyncResult = await syncYahoo();
            toast.success(`Yahoo sync: ${result.inserted} inserted, ${result.updated} updated`);
        } catch {
            toast.error('Yahoo sync failed');
        } finally {
            setSyncing(null);
        }
    }

    if (statsLoading) return <div>Loading dashboard...</div>;

    return (
        <div>
            <h2 style={{ marginBottom: '1.5rem' }}>Dashboard</h2>

            {/* Row 1: Users & Tenants */}
            <div style={{ display: 'flex', gap: '1rem', marginBottom: '1rem', flexWrap: 'wrap' }}>
                <StatCard label="Total Users" value={stats?.total_users ?? 0} />
                <StatCard label="Active Users (30d)" value={stats?.active_users ?? 0} />
                <StatCard label="Tenants" value={stats?.total_tenants ?? 0} />
            </div>

            {/* Row 2: Data */}
            <div style={{ display: 'flex', gap: '1rem', marginBottom: '1rem', flexWrap: 'wrap' }}>
                <StatCard label="Accounts" value={stats?.total_accounts ?? 0} />
                <StatCard label="Holdings" value={stats?.total_holdings ?? 0} />
                <StatCard label="Transactions" value={stats?.total_transactions ?? 0} />
                <StatCard label="Database Size" value={stats?.database_size ?? '-'} />
            </div>

            {/* Row 3: Prices */}
            <div style={{ display: 'flex', gap: '1rem', marginBottom: '1.5rem', flexWrap: 'wrap' }}>
                <StatCard label="Symbols Tracked" value={stats?.symbols_tracked ?? 0} />
                <StatCard label="Stale Symbols" value={stats?.stale_symbols ?? 0} />
                <div style={{ ...statCardStyle, display: 'flex', flexDirection: 'column', gap: '0.5rem' }}>
                    <button
                        onClick={handleSyncFinnhub}
                        disabled={syncing !== null}
                        style={{
                            padding: '0.4rem 0.8rem',
                            background: syncing === 'finnhub' ? '#999' : '#1976d2',
                            color: '#fff',
                            border: 'none',
                            borderRadius: '4px',
                            cursor: syncing !== null ? 'default' : 'pointer',
                            fontSize: '0.85rem',
                        }}
                    >
                        {syncing === 'finnhub' ? 'Syncing...' : 'Sync Finnhub'}
                    </button>
                    <button
                        onClick={handleSyncYahoo}
                        disabled={syncing !== null}
                        style={{
                            padding: '0.4rem 0.8rem',
                            background: syncing === 'yahoo' ? '#999' : '#2e7d32',
                            color: '#fff',
                            border: 'none',
                            borderRadius: '4px',
                            cursor: syncing !== null ? 'default' : 'pointer',
                            fontSize: '0.85rem',
                        }}
                    >
                        {syncing === 'yahoo' ? 'Syncing...' : 'Sync Yahoo'}
                    </button>
                </div>
            </div>

            {/* Login Activity */}
            <div style={cardStyle}>
                <h3 style={{ marginBottom: '1rem' }}>Recent Login Activity</h3>
                {activityLoading ? (
                    <div>Loading...</div>
                ) : (
                    <table style={{ width: '100%', borderCollapse: 'collapse' }}>
                        <thead>
                            <tr style={{ borderBottom: '2px solid #e0e0e0' }}>
                                <th style={{ textAlign: 'left', padding: '0.5rem' }}>Email</th>
                                <th style={{ textAlign: 'left', padding: '0.5rem' }}>Time</th>
                                <th style={{ textAlign: 'left', padding: '0.5rem' }}>IP Address</th>
                                <th style={{ textAlign: 'center', padding: '0.5rem' }}>Status</th>
                            </tr>
                        </thead>
                        <tbody>
                            {activity?.map((a, i) => (
                                <tr key={i} style={{ borderBottom: '1px solid #f0f0f0' }}>
                                    <td style={{ padding: '0.5rem' }}>{a.user_email}</td>
                                    <td style={{ padding: '0.5rem', fontSize: '0.85rem' }}>
                                        {new Date(a.created_at).toLocaleString()}
                                    </td>
                                    <td style={{ padding: '0.5rem', fontSize: '0.85rem', color: '#666' }}>
                                        {a.ip_address ?? '-'}
                                    </td>
                                    <td style={{ padding: '0.5rem', textAlign: 'center' }}>
                                        <span style={{
                                            padding: '0.15rem 0.4rem',
                                            borderRadius: '4px',
                                            fontSize: '0.8rem',
                                            background: a.success ? '#e8f5e9' : '#ffebee',
                                            color: a.success ? '#2e7d32' : '#c62828',
                                        }}>
                                            {a.success ? 'Success' : 'Failed'}
                                        </span>
                                    </td>
                                </tr>
                            ))}
                            {(!activity || activity.length === 0) && (
                                <tr>
                                    <td colSpan={4} style={{ padding: '1rem', color: '#999', textAlign: 'center' }}>
                                        No login activity recorded
                                    </td>
                                </tr>
                            )}
                        </tbody>
                    </table>
                )}
            </div>
        </div>
    );
}
