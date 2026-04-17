import { useMemo, useState, useEffect } from 'react';
import { AreaChart, Area, XAxis, YAxis, Tooltip, Legend, ResponsiveContainer } from 'recharts';
import { getSnapshotProjection } from '../api/dashboard';
import { formatCurrency } from '../utils/format';
import { cardStyle, selectStyle } from '../utils/styles';
import { extractErrorMessage } from '../utils/errorMessage';
import type { SnapshotProjection } from '../types/dashboard';

const PROJECTION_HORIZONS = [
    { value: 5, label: '5 Years' },
    { value: 10, label: '10 Years' },
    { value: 15, label: '15 Years' },
    { value: 20, label: '20 Years' },
];

const chartCardStyle = { ...cardStyle, marginBottom: '2rem' };
const horizonSelectStyle = { ...selectStyle, padding: '0.3rem 0.5rem', fontSize: '0.85rem' };

export default function SnapshotProjectionChart() {
    const [years, setYears] = useState(10);
    const [data, setData] = useState<SnapshotProjection | null>(null);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState<string | null>(null);

    useEffect(() => {
        let cancelled = false;
        setLoading(true);
        setError(null);
        getSnapshotProjection(years)
            .then((result) => { if (!cancelled) { setData(result); setLoading(false); } })
            .catch((err) => { if (!cancelled) { setError(extractErrorMessage(err)); setLoading(false); } });
        return () => { cancelled = true; };
    }, [years]);

    const chartData = useMemo(() => {
        if (!data?.data_points) return [];
        return data.data_points.map((dp) => ({
            date: dp.date,
            year: dp.year,
            investmentValue: dp.investment_value,
            propertyEquity: dp.property_equity,
        }));
    }, [data]);

    const formatYear = (dateStr: string) => {
        const date = new Date(dateStr + 'T00:00:00');
        return date.getFullYear().toString();
    };

    const cagrPct = data ? (data.portfolio_cagr * 100).toFixed(1) : '0';

    return (
        <div style={chartCardStyle}>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', marginBottom: '0.5rem' }}>
                <h3>Snapshot Forward Projection</h3>
                <select
                    value={years}
                    onChange={(e) => setYears(Number(e.target.value))}
                    style={horizonSelectStyle}
                    aria-label="Projection horizon"
                >
                    {PROJECTION_HORIZONS.map(h => (
                        <option key={h.value} value={h.value}>{h.label}</option>
                    ))}
                </select>
            </div>

            {loading ? (
                <div style={{ color: '#999', textAlign: 'center', padding: '2rem 0' }}>Loading...</div>
            ) : error ? (
                <div style={{ color: '#c62828', textAlign: 'center', padding: '2rem 0' }}>
                    Failed to load projection: {error}
                </div>
            ) : !data || data.data_points.length === 0 ? (
                <div style={{ color: '#999', textAlign: 'center', padding: '2rem 0' }}>
                    No projection data available.
                </div>
            ) : (
                <>
                    <div style={{ color: '#999', fontSize: '0.85rem', marginBottom: '0.5rem' }}>
                        Based on 10-year historical returns ({cagrPct}% avg CAGR)
                        {data.investment_account_count > 0 && ` \u2022 ${data.investment_account_count} account${data.investment_account_count > 1 ? 's' : ''}`}
                        {data.property_count > 0 && ` \u2022 ${data.property_count} propert${data.property_count > 1 ? 'ies' : 'y'}`}
                    </div>
                    <ResponsiveContainer width="100%" height={350}>
                        <AreaChart data={chartData} margin={{ top: 5, right: 20, bottom: 5, left: 20 }}>
                            <defs>
                                <linearGradient id="colorProjInvestment" x1="0" y1="0" x2="0" y2="1">
                                    <stop offset="5%" stopColor="#1976d2" stopOpacity={0.3} />
                                    <stop offset="95%" stopColor="#1976d2" stopOpacity={0} />
                                </linearGradient>
                                <linearGradient id="colorProjProperty" x1="0" y1="0" x2="0" y2="1">
                                    <stop offset="5%" stopColor="#2e7d32" stopOpacity={0.3} />
                                    <stop offset="95%" stopColor="#2e7d32" stopOpacity={0} />
                                </linearGradient>
                            </defs>
                            <XAxis
                                dataKey="date"
                                tickFormatter={formatYear}
                                tick={{ fontSize: 12 }}
                            />
                            <YAxis
                                tickFormatter={(v: number) => `$${(v / 1000).toFixed(0)}k`}
                                tick={{ fontSize: 12 }}
                                width={60}
                            />
                            <Tooltip
                                formatter={(value, name) => [
                                    formatCurrency(Number(value)),
                                    String(name),
                                ]}
                                labelFormatter={(label) => {
                                    const dateStr = String(label);
                                    const date = new Date(dateStr + 'T00:00:00');
                                    const formatted = date.toLocaleDateString('en-US', { month: 'long', day: 'numeric', year: 'numeric' });
                                    const dp = chartData.find(d => d.date === dateStr);
                                    const total = dp ? dp.investmentValue + dp.propertyEquity : 0;
                                    return `${formatted} — Total: ${formatCurrency(total)}`;
                                }}
                            />
                            <Legend />
                            <Area
                                type="monotone"
                                dataKey="investmentValue"
                                name="Projected Investments"
                                stackId="1"
                                stroke="#1976d2"
                                strokeWidth={2}
                                fill="url(#colorProjInvestment)"
                            />
                            <Area
                                type="monotone"
                                dataKey="propertyEquity"
                                name="Projected Property Equity"
                                stackId="1"
                                stroke="#2e7d32"
                                strokeWidth={2}
                                fill="url(#colorProjProperty)"
                            />
                        </AreaChart>
                    </ResponsiveContainer>
                </>
            )}
        </div>
    );
}
