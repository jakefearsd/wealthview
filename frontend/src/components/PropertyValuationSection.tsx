import { useMemo } from 'react';
import { LineChart, Line, XAxis, YAxis, Tooltip, ResponsiveContainer, Legend } from 'recharts';
import { formatCurrency } from '../utils/format';
import { cardStyle } from '../utils/styles';
import type { PropertyValuation, ZillowSearchResult } from '../types/property';

interface PropertyValuationSectionProps {
    valuations: PropertyValuation[] | null;
    canWrite: boolean;
    refreshing: boolean;
    zillowCandidates: ZillowSearchResult[] | null;
    onRefreshValuation: () => void;
    onSelectZpid: (zpid: string) => void;
    onDismissCandidates: () => void;
}

export default function PropertyValuationSection({
    valuations,
    canWrite,
    refreshing,
    zillowCandidates,
    onRefreshValuation,
    onSelectZpid,
    onDismissCandidates,
}: PropertyValuationSectionProps) {
    const valuationChartData = useMemo(() => {
        if (!valuations) return [];
        return [...valuations].reverse().map((v) => ({
            date: v.valuation_date,
            value: v.value,
            source: v.source,
        }));
    }, [valuations]);

    const badgeStyle = useMemo(() => (color: string, bg: string) => ({
        display: 'inline-block',
        padding: '0.15rem 0.5rem',
        background: bg,
        color: color,
        borderRadius: '4px',
        fontSize: '0.75rem',
        fontWeight: 600 as const,
    }), []);

    return (
        <>
            {valuationChartData.length > 0 && (
                <div style={{ ...cardStyle, marginBottom: '2rem' }}>
                    <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '1rem' }}>
                        <h3>Valuation History</h3>
                        {canWrite && (
                            <button
                                onClick={onRefreshValuation}
                                disabled={refreshing}
                                style={{ padding: '0.4rem 0.8rem', background: refreshing ? '#ccc' : '#1976d2', color: '#fff', border: 'none', borderRadius: '4px', cursor: refreshing ? 'default' : 'pointer', fontSize: '0.85rem' }}
                            >
                                {refreshing ? 'Refreshing...' : 'Refresh Valuation'}
                            </button>
                        )}
                    </div>
                    <ResponsiveContainer width="100%" height={250}>
                        <LineChart data={valuationChartData}>
                            <XAxis dataKey="date" />
                            <YAxis />
                            <Tooltip formatter={(value: number) => formatCurrency(value)} />
                            <Legend />
                            <Line type="monotone" dataKey="value" name="Value" stroke="#1976d2" strokeWidth={2} dot={{ r: 4 }} />
                        </LineChart>
                    </ResponsiveContainer>
                    <table style={{ width: '100%', marginTop: '1rem', fontSize: '0.85rem', borderCollapse: 'collapse' }}>
                        <thead>
                            <tr style={{ borderBottom: '2px solid #eee', textAlign: 'left' }}>
                                <th style={{ padding: '0.5rem' }}>Date</th>
                                <th style={{ padding: '0.5rem' }}>Value</th>
                                <th style={{ padding: '0.5rem' }}>Source</th>
                            </tr>
                        </thead>
                        <tbody>
                            {valuations?.map((v) => (
                                <tr key={v.id} style={{ borderBottom: '1px solid #f0f0f0' }}>
                                    <td style={{ padding: '0.5rem' }}>{v.valuation_date}</td>
                                    <td style={{ padding: '0.5rem' }}>{formatCurrency(v.value)}</td>
                                    <td style={{ padding: '0.5rem' }}>
                                        <span style={badgeStyle(
                                            v.source === 'zillow' ? '#e65100' : v.source === 'appraisal' ? '#1b5e20' : '#444',
                                            v.source === 'zillow' ? '#fff3e0' : v.source === 'appraisal' ? '#e8f5e9' : '#f5f5f5'
                                        )}>{v.source}</span>
                                    </td>
                                </tr>
                            ))}
                        </tbody>
                    </table>
                </div>
            )}

            {valuationChartData.length === 0 && canWrite && (
                <div style={{ ...cardStyle, marginBottom: '2rem', textAlign: 'center', color: '#999' }}>
                    <p>No valuation history yet.</p>
                    <button
                        onClick={onRefreshValuation}
                        disabled={refreshing}
                        style={{ marginTop: '0.5rem', padding: '0.4rem 0.8rem', background: refreshing ? '#ccc' : '#1976d2', color: '#fff', border: 'none', borderRadius: '4px', cursor: refreshing ? 'default' : 'pointer', fontSize: '0.85rem' }}
                    >
                        {refreshing ? 'Refreshing...' : 'Refresh Valuation'}
                    </button>
                </div>
            )}

            {zillowCandidates && (
                <div style={{
                    position: 'fixed', top: 0, left: 0, right: 0, bottom: 0,
                    background: 'rgba(0,0,0,0.5)', display: 'flex',
                    alignItems: 'center', justifyContent: 'center', zIndex: 1000,
                }}>
                    <div style={{ ...cardStyle, maxWidth: '600px', width: '90%', maxHeight: '80vh', overflow: 'auto' }}>
                        <h3 style={{ marginBottom: '0.5rem' }}>Multiple Properties Found</h3>
                        <p style={{ color: '#666', fontSize: '0.9rem', marginBottom: '1rem' }}>
                            Zillow found multiple properties matching this address. Please select the correct one:
                        </p>
                        <div style={{ display: 'grid', gap: '0.75rem' }}>
                            {zillowCandidates.map((c) => (
                                <button
                                    key={c.zpid}
                                    onClick={() => onSelectZpid(c.zpid)}
                                    style={{
                                        display: 'flex', justifyContent: 'space-between', alignItems: 'center',
                                        padding: '1rem', background: '#f9f9f9', border: '1px solid #ddd',
                                        borderRadius: '8px', cursor: 'pointer', textAlign: 'left', width: '100%',
                                    }}
                                >
                                    <div>
                                        <div style={{ fontWeight: 600 }}>{c.address}</div>
                                        <div style={{ fontSize: '0.8rem', color: '#888' }}>ZPID: {c.zpid}</div>
                                    </div>
                                    <div style={{ fontWeight: 600, color: '#2e7d32', fontSize: '1.1rem' }}>
                                        {formatCurrency(c.zestimate)}
                                    </div>
                                </button>
                            ))}
                        </div>
                        <button
                            onClick={onDismissCandidates}
                            style={{
                                marginTop: '1rem', padding: '0.5rem 1rem', background: '#eee',
                                border: 'none', borderRadius: '4px', cursor: 'pointer', width: '100%',
                            }}
                        >
                            Cancel
                        </button>
                    </div>
                </div>
            )}
        </>
    );
}
