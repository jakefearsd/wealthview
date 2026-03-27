import { useState, useEffect } from 'react';
import { getAuditLogs } from '../../api/audit';
import { cardStyle } from '../../utils/styles';
import type { AuditLogEntry } from '../../types/audit';

const ENTITY_TYPES = ['', 'account', 'transaction', 'holding', 'property', 'user', 'tenant'];

export default function AuditLogSection() {
    const [entries, setEntries] = useState<AuditLogEntry[]>([]);
    const [loading, setLoading] = useState(true);
    const [filter, setFilter] = useState('');
    const [page, setPage] = useState(0);
    const [total, setTotal] = useState(0);

    useEffect(() => {
        setLoading(true);
        getAuditLogs(page, 50, filter || undefined)
            .then((res) => {
                setEntries(res.data);
                setTotal(res.total);
            })
            .catch(() => setEntries([]))
            .finally(() => setLoading(false));
    }, [page, filter]);

    return (
        <div>
            <h2 style={{ marginBottom: '1.5rem' }}>Audit Log</h2>

            <div style={{ marginBottom: '1rem' }}>
                <label style={{ marginRight: '0.5rem' }}>Filter by type:</label>
                <select
                    value={filter}
                    onChange={(e) => { setFilter(e.target.value); setPage(0); }}
                    style={{ padding: '0.4rem', border: '1px solid #ccc', borderRadius: '4px' }}
                >
                    <option value="">All</option>
                    {ENTITY_TYPES.filter(Boolean).map((t) => (
                        <option key={t} value={t}>{t}</option>
                    ))}
                </select>
            </div>

            <div style={cardStyle}>
                {loading ? (
                    <div>Loading...</div>
                ) : (
                    <>
                        <table style={{ width: '100%', borderCollapse: 'collapse' }}>
                            <thead>
                                <tr style={{ borderBottom: '2px solid #e0e0e0' }}>
                                    <th style={{ textAlign: 'left', padding: '0.5rem' }}>Time</th>
                                    <th style={{ textAlign: 'left', padding: '0.5rem' }}>Action</th>
                                    <th style={{ textAlign: 'left', padding: '0.5rem' }}>Entity Type</th>
                                    <th style={{ textAlign: 'left', padding: '0.5rem' }}>Details</th>
                                </tr>
                            </thead>
                            <tbody>
                                {entries.map((e) => (
                                    <tr key={e.id} style={{ borderBottom: '1px solid #f0f0f0' }}>
                                        <td style={{ padding: '0.5rem', fontSize: '0.85rem' }}>
                                            {new Date(e.created_at).toLocaleString()}
                                        </td>
                                        <td style={{ padding: '0.5rem' }}>{e.action}</td>
                                        <td style={{ padding: '0.5rem' }}>{e.entity_type}</td>
                                        <td style={{ padding: '0.5rem', fontSize: '0.85rem', color: '#666' }}>
                                            {e.details ? JSON.stringify(e.details) : '-'}
                                        </td>
                                    </tr>
                                ))}
                                {entries.length === 0 && (
                                    <tr><td colSpan={4} style={{ padding: '1rem', color: '#999', textAlign: 'center' }}>No audit log entries</td></tr>
                                )}
                            </tbody>
                        </table>
                        <div style={{ marginTop: '1rem', display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                            <span style={{ color: '#666', fontSize: '0.85rem' }}>{total} total entries</span>
                            <div>
                                <button
                                    onClick={() => setPage((p) => Math.max(0, p - 1))}
                                    disabled={page === 0}
                                    style={{ marginRight: '0.5rem', padding: '0.3rem 0.6rem', cursor: page === 0 ? 'default' : 'pointer' }}
                                >
                                    Previous
                                </button>
                                <span style={{ marginRight: '0.5rem' }}>Page {page + 1}</span>
                                <button
                                    onClick={() => setPage((p) => p + 1)}
                                    disabled={(page + 1) * 50 >= total}
                                    style={{ padding: '0.3rem 0.6rem', cursor: (page + 1) * 50 >= total ? 'default' : 'pointer' }}
                                >
                                    Next
                                </button>
                            </div>
                        </div>
                    </>
                )}
            </div>
        </div>
    );
}
