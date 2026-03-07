import { useMemo, useState, useEffect } from 'react';
import { AreaChart, Area, XAxis, YAxis, Tooltip, ResponsiveContainer } from 'recharts';
import { getTheoreticalHistory } from '../api/accounts';
import { formatCurrency } from '../utils/format';
import { cardStyle } from '../utils/styles';
import type { PortfolioHistory } from '../types/portfolio';

interface Props {
    accountId: string;
    accountType: string;
}

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

export default function TheoreticalPortfolioChart({ accountId, accountType }: Props) {
    const [years, setYears] = useState(2);
    const [data, setData] = useState<PortfolioHistory | null>(null);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState(false);

    useEffect(() => {
        let cancelled = false;
        setLoading(true);
        setError(false);
        getTheoreticalHistory(accountId, years)
            .then((result) => { if (!cancelled) { setData(result); setLoading(false); } })
            .catch(() => { if (!cancelled) { setError(true); setLoading(false); } });
        return () => { cancelled = true; };
    }, [accountId, years]);

    const chartData = useMemo(() => {
        if (!data?.data_points) return [];
        return data.data_points.map((dp) => ({
            date: dp.date,
            value: dp.total_value,
        }));
    }, [data]);

    if (accountType === 'bank') {
        return (
            <div style={chartCardStyle}>
                <h3 style={{ marginBottom: '1rem' }}>Theoretical Portfolio History</h3>
                <div style={{ color: '#999', textAlign: 'center', padding: '2rem 0' }}>
                    Portfolio history is not available for bank accounts.
                </div>
            </div>
        );
    }

    const tickInterval = Math.max(1, Math.floor(chartData.length / 10));
    const selectedLabel = TIME_HORIZONS.find(h => h.value === years)?.label ?? `${years} years`;

    return (
        <div style={chartCardStyle}>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', marginBottom: '0.5rem' }}>
                <h3>Theoretical Portfolio History</h3>
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
                    No price data available for current holdings.
                </div>
            ) : (
                <>
                    <div style={{ color: '#999', fontSize: '0.85rem', marginBottom: '0.5rem' }}>
                        What your current holdings ({data.symbols.join(', ')}) would have been worth over the past {selectedLabel.toLowerCase()}
                    </div>
                    {data.has_money_market_holdings && (
                        <div style={{ color: '#7b6900', fontSize: '0.8rem', marginBottom: '1rem', background: '#fffde7', padding: '0.4rem 0.6rem', borderRadius: '4px', display: 'inline-block' }}>
                            Money market holdings are projected at constant $1.00/share in historical views.
                        </div>
                    )}
                    <ResponsiveContainer width="100%" height={350}>
                        <AreaChart data={chartData} margin={{ top: 5, right: 20, bottom: 5, left: 20 }}>
                            <defs>
                                <linearGradient id="colorValue" x1="0" y1="0" x2="0" y2="1">
                                    <stop offset="5%" stopColor="#1976d2" stopOpacity={0.3} />
                                    <stop offset="95%" stopColor="#1976d2" stopOpacity={0} />
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
                                formatter={(value: number) => [formatCurrency(value), 'Value']}
                                labelFormatter={(label: string) => new Date(label + 'T00:00:00').toLocaleDateString('en-US', { month: 'long', day: 'numeric', year: 'numeric' })}
                            />
                            <Area
                                type="monotone"
                                dataKey="value"
                                stroke="#1976d2"
                                strokeWidth={2}
                                fill="url(#colorValue)"
                            />
                        </AreaChart>
                    </ResponsiveContainer>
                </>
            )}
        </div>
    );
}
