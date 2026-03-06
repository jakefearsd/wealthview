import { useMemo } from 'react';
import { AreaChart, Area, XAxis, YAxis, Tooltip, ResponsiveContainer } from 'recharts';
import { getTheoreticalHistory } from '../api/accounts';
import { useApiQuery } from '../hooks/useApiQuery';
import { formatCurrency } from '../utils/format';
import { cardStyle } from '../utils/styles';

interface Props {
    accountId: string;
    accountType: string;
}

function formatDate(dateStr: string): string {
    const date = new Date(dateStr + 'T00:00:00');
    return date.toLocaleDateString('en-US', { month: 'short', year: '2-digit' });
}

const chartCardStyle = { ...cardStyle, marginBottom: '2rem' };

export default function TheoreticalPortfolioChart({ accountId, accountType }: Props) {
    const { data, loading, error } = useApiQuery(() => getTheoreticalHistory(accountId));

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

    if (loading) {
        return (
            <div style={chartCardStyle}>
                <h3 style={{ marginBottom: '1rem' }}>Theoretical Portfolio History</h3>
                <div style={{ color: '#999', textAlign: 'center', padding: '2rem 0' }}>Loading...</div>
            </div>
        );
    }

    if (error || !data || data.data_points.length === 0) {
        return (
            <div style={chartCardStyle}>
                <h3 style={{ marginBottom: '1rem' }}>Theoretical Portfolio History</h3>
                <div style={{ color: '#999', textAlign: 'center', padding: '2rem 0' }}>
                    No price data available for current holdings.
                </div>
            </div>
        );
    }

    const tickInterval = Math.max(1, Math.floor(chartData.length / 10));

    return (
        <div style={chartCardStyle}>
            <h3 style={{ marginBottom: '0.5rem' }}>Theoretical Portfolio History</h3>
            <div style={{ color: '#999', fontSize: '0.85rem', marginBottom: '1rem' }}>
                What your current holdings ({data.symbols.join(', ')}) would have been worth over the past 2 years
            </div>
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
        </div>
    );
}
