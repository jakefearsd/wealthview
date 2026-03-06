import { getDashboardSummary } from '../api/dashboard';
import { useApiQuery } from '../hooks/useApiQuery';
import { formatCurrency } from '../utils/format';
import { cardStyle } from '../utils/styles';
import { PieChart, Pie, Cell, Tooltip, ResponsiveContainer } from 'recharts';
import SummaryCard from '../components/SummaryCard';

const COLORS = ['#1976d2', '#2e7d32', '#ed6c02', '#9c27b0', '#d32f2f', '#0097a7'];

export default function DashboardPage() {
    const { data, loading, error } = useApiQuery(getDashboardSummary);

    if (loading) return <div>Loading dashboard...</div>;
    if (error) return <div style={{ color: '#d32f2f' }}>Error: {error}</div>;
    if (!data) return null;

    return (
        <div>
            <h2 style={{ marginBottom: '1.5rem' }}>Dashboard</h2>

            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(200px, 1fr))', gap: '1rem', marginBottom: '2rem' }}>
                <SummaryCard label="Net Worth" value={formatCurrency(data.net_worth)} large />
                <SummaryCard label="Investments" value={formatCurrency(data.total_investments)} />
                <SummaryCard label="Cash" value={formatCurrency(data.total_cash)} />
                <SummaryCard label="Property Equity" value={formatCurrency(data.total_property_equity)} />
            </div>

            <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '1.5rem' }}>
                <div style={cardStyle}>
                    <h3 style={{ marginBottom: '1rem' }}>Accounts</h3>
                    <table style={{ width: '100%', borderCollapse: 'collapse' }}>
                        <thead>
                            <tr style={{ borderBottom: '2px solid #e0e0e0' }}>
                                <th style={{ textAlign: 'left', padding: '0.5rem' }}>Name</th>
                                <th style={{ textAlign: 'left', padding: '0.5rem' }}>Type</th>
                                <th style={{ textAlign: 'right', padding: '0.5rem' }}>Balance</th>
                            </tr>
                        </thead>
                        <tbody>
                            {data.accounts.map((acct, i) => (
                                <tr key={i} style={{ borderBottom: '1px solid #f0f0f0' }}>
                                    <td style={{ padding: '0.5rem' }}>{acct.name}</td>
                                    <td style={{ padding: '0.5rem' }}>{acct.type}</td>
                                    <td style={{ padding: '0.5rem', textAlign: 'right' }}>{formatCurrency(acct.balance)}</td>
                                </tr>
                            ))}
                        </tbody>
                    </table>
                </div>

                <div style={cardStyle}>
                    <h3 style={{ marginBottom: '1rem' }}>Allocation</h3>
                    {data.allocation.length > 0 ? (
                        <ResponsiveContainer width="100%" height={250}>
                            <PieChart>
                                <Pie data={data.allocation} dataKey="value" nameKey="category" cx="50%" cy="50%" outerRadius={90} label={({ category, percentage }) => `${category} ${percentage}%`}>
                                    {data.allocation.map((_, index) => (
                                        <Cell key={index} fill={COLORS[index % COLORS.length]} />
                                    ))}
                                </Pie>
                                <Tooltip formatter={(value: number) => formatCurrency(value)} />
                            </PieChart>
                        </ResponsiveContainer>
                    ) : (
                        <div style={{ color: '#999', textAlign: 'center', padding: '3rem' }}>No allocation data</div>
                    )}
                </div>
            </div>
        </div>
    );
}
