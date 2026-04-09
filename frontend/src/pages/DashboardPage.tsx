import { getDashboardSummary } from '../api/dashboard';
import { useApiQuery } from '../hooks/useApiQuery';
import { formatCurrency } from '../utils/format';
import { cardStyle, tableStyle, thStyle, tdStyle, trHoverStyle } from '../utils/styles';
import LoadingState from '../components/LoadingState';
import ErrorState from '../components/ErrorState';
import { PieChart, Pie, Cell, Tooltip, ResponsiveContainer } from 'recharts';
import SummaryCard from '../components/SummaryCard';
import CombinedPortfolioChart from '../components/CombinedPortfolioChart';
import SnapshotProjectionChart from '../components/SnapshotProjectionChart';

const COLORS = ['#1976d2', '#2e7d32', '#ed6c02', '#9c27b0', '#d32f2f', '#0097a7'];

export default function DashboardPage() {
    const { data, loading, error } = useApiQuery(getDashboardSummary);

    if (loading) return <LoadingState message="Loading dashboard..." />;
    if (error) return <ErrorState message={error} onRetry={() => window.location.reload()} />;
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

            <CombinedPortfolioChart />

            <SnapshotProjectionChart />

            <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '1.5rem' }}>
                <div style={cardStyle}>
                    <h3 style={{ marginBottom: '1rem' }}>Accounts</h3>
                    <table style={tableStyle}>
                        <thead>
                            <tr>
                                <th style={thStyle}>Name</th>
                                <th style={thStyle}>Type</th>
                                <th style={{ ...thStyle, textAlign: 'right' }}>Balance</th>
                            </tr>
                        </thead>
                        <tbody>
                            {data.accounts.map((acct, i) => (
                                <tr key={i} style={trHoverStyle}>
                                    <td style={tdStyle}>{acct.name}</td>
                                    <td style={tdStyle}>{acct.type}</td>
                                    <td style={{ ...tdStyle, textAlign: 'right' }}>{formatCurrency(acct.balance)}</td>
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
                                <Pie data={data.allocation} dataKey="value" nameKey="category" cx="50%" cy="50%" outerRadius={90} label={(props) => {
                                    const entry = (props as { payload?: { category: string; percentage: number } }).payload;
                                    return entry ? `${entry.category} ${entry.percentage}%` : '';
                                }}>
                                    {data.allocation.map((_, index) => (
                                        <Cell key={index} fill={COLORS[index % COLORS.length]} />
                                    ))}
                                </Pie>
                                <Tooltip formatter={(value) => formatCurrency(Number(value))} />
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
