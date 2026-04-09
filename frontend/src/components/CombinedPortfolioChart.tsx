import { useMemo, useState, useEffect } from 'react';
import { AreaChart, Area, XAxis, YAxis, Tooltip, Legend, ResponsiveContainer } from 'recharts';
import { getCombinedPortfolioHistory } from '../api/dashboard';
import { formatCurrency } from '../utils/format';
import { cardStyle } from '../utils/styles';
import type { CombinedPortfolioHistory } from '../types/dashboard';

const TIME_HORIZONS = [
    { value: 1, label: '1 Year' },
    { value: 2, label: '2 Years' },
    { value: 3, label: '3 Years' },
    { value: 5, label: '5 Years' },
    { value: 10, label: '10 Years' },
];

function formatDate(dateStr: string): string {
    const date = new Date(dateStr + 'T00:00:00');
    return date.toLocaleDateString('en-US', { month: 'short', year: '2-digit' });
}

const chartCardStyle = { ...cardStyle, marginBottom: '2rem' };
const selectStyle = {
    padding: '0.3rem 0.5rem',
    border: '1px solid #ccc',
    borderRadius: '4px',
    fontSize: '0.85rem',
    background: '#fff',
    cursor: 'pointer',
};

export default function CombinedPortfolioChart() {
    const [years, setYears] = useState(2);
    const [data, setData] = useState<CombinedPortfolioHistory | null>(null);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState(false);

    useEffect(() => {
        let cancelled = false;
        setLoading(true);
        setError(false);
        getCombinedPortfolioHistory(years)
            .then((result) => { if (!cancelled) { setData(result); setLoading(false); } })
            .catch(() => { if (!cancelled) { setError(true); setLoading(false); } });
        return () => { cancelled = true; };
    }, [years]);

    const chartData = useMemo(() => {
        if (!data?.data_points) return [];
        return data.data_points.map((dp) => ({
            date: dp.date,
            investmentValue: dp.investment_value,
            propertyEquity: dp.property_equity,
        }));
    }, [data]);

    const tickInterval = Math.max(1, Math.floor(chartData.length / 10));

    return (
        <div style={chartCardStyle}>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', marginBottom: '0.5rem' }}>
                <h3>Combined Portfolio History</h3>
                <select
                    value={years}
                    onChange={(e) => setYears(Number(e.target.value))}
                    style={selectStyle}
                    aria-label="Time horizon"
                >
                    {TIME_HORIZONS.map(h => (
                        <option key={h.value} value={h.value}>{h.label}</option>
                    ))}
                </select>
            </div>

            {loading ? (
                <div style={{ color: '#999', textAlign: 'center', padding: '2rem 0' }}>Loading...</div>
            ) : error || !data || data.data_points.length === 0 ? (
                <div style={{ color: '#999', textAlign: 'center', padding: '2rem 0' }}>
                    No portfolio history data available.
                </div>
            ) : (
                <>
                    <div style={{ color: '#999', fontSize: '0.85rem', marginBottom: '0.5rem' }}>
                        {data.investment_account_count > 0 && data.property_count > 0
                            ? `${data.investment_account_count} investment account${data.investment_account_count > 1 ? 's' : ''} + ${data.property_count} propert${data.property_count > 1 ? 'ies' : 'y'}`
                            : data.investment_account_count > 0
                                ? `${data.investment_account_count} investment account${data.investment_account_count > 1 ? 's' : ''}`
                                : `${data.property_count} propert${data.property_count > 1 ? 'ies' : 'y'}`}
                    </div>
                    <ResponsiveContainer width="100%" height={350}>
                        <AreaChart data={chartData} margin={{ top: 5, right: 20, bottom: 5, left: 20 }}>
                            <defs>
                                <linearGradient id="colorInvestment" x1="0" y1="0" x2="0" y2="1">
                                    <stop offset="5%" stopColor="#1976d2" stopOpacity={0.3} />
                                    <stop offset="95%" stopColor="#1976d2" stopOpacity={0} />
                                </linearGradient>
                                <linearGradient id="colorProperty" x1="0" y1="0" x2="0" y2="1">
                                    <stop offset="5%" stopColor="#2e7d32" stopOpacity={0.3} />
                                    <stop offset="95%" stopColor="#2e7d32" stopOpacity={0} />
                                </linearGradient>
                            </defs>
                            <XAxis
                                dataKey="date"
                                tickFormatter={formatDate}
                                interval={tickInterval}
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
                                name="Investments"
                                stackId="1"
                                stroke="#1976d2"
                                strokeWidth={2}
                                fill="url(#colorInvestment)"
                            />
                            <Area
                                type="monotone"
                                dataKey="propertyEquity"
                                name="Property Equity"
                                stackId="1"
                                stroke="#2e7d32"
                                strokeWidth={2}
                                fill="url(#colorProperty)"
                            />
                        </AreaChart>
                    </ResponsiveContainer>
                </>
            )}
        </div>
    );
}
