import { getDashboardSummary } from '../api/dashboard';
import { useApiQuery } from '../hooks/useApiQuery';
import { PieChart, Pie, Cell, Tooltip, ResponsiveContainer } from 'recharts';

const COLORS = ['#1976d2', '#2e7d32', '#ed6c02', '#9c27b0', '#d32f2f', '#0097a7'];

function formatCurrency(value: number): string {
    return new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD' }).format(value);
}

export default function DashboardPage() {
    const { data, loading, error } = useApiQuery(getDashboardSummary);

    if (loading) return <div>Loading dashboard...</div>;
    if (error) return <div style={{ color: '#d32f2f' }}>Error: {error}</div>;
    if (!data) return null;

    return (
        <div>
            <h2 style={{ marginBottom: '1.5rem' }}>Dashboard</h2>

            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(200px, 1fr))', gap: '1rem', marginBottom: '2rem' }}>
                <div style={{ background: '#fff', padding: '1.5rem', borderRadius: '8px', boxShadow: '0 1px 3px rgba(0,0,0,0.1)' }}>
                    <div style={{ color: '#666', fontSize: '0.85rem' }}>Net Worth</div>
                    <div style={{ fontSize: '1.75rem', fontWeight: 700, color: '#1a1a2e' }}>{formatCurrency(data.net_worth)}</div>
                </div>
                <div style={{ background: '#fff', padding: '1.5rem', borderRadius: '8px', boxShadow: '0 1px 3px rgba(0,0,0,0.1)' }}>
                    <div style={{ color: '#666', fontSize: '0.85rem' }}>Investments</div>
                    <div style={{ fontSize: '1.5rem', fontWeight: 600 }}>{formatCurrency(data.total_investments)}</div>
                </div>
                <div style={{ background: '#fff', padding: '1.5rem', borderRadius: '8px', boxShadow: '0 1px 3px rgba(0,0,0,0.1)' }}>
                    <div style={{ color: '#666', fontSize: '0.85rem' }}>Cash</div>
                    <div style={{ fontSize: '1.5rem', fontWeight: 600 }}>{formatCurrency(data.total_cash)}</div>
                </div>
                <div style={{ background: '#fff', padding: '1.5rem', borderRadius: '8px', boxShadow: '0 1px 3px rgba(0,0,0,0.1)' }}>
                    <div style={{ color: '#666', fontSize: '0.85rem' }}>Property Equity</div>
                    <div style={{ fontSize: '1.5rem', fontWeight: 600 }}>{formatCurrency(data.total_property_equity)}</div>
                </div>
            </div>

            <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '1.5rem' }}>
                <div style={{ background: '#fff', padding: '1.5rem', borderRadius: '8px', boxShadow: '0 1px 3px rgba(0,0,0,0.1)' }}>
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

                <div style={{ background: '#fff', padding: '1.5rem', borderRadius: '8px', boxShadow: '0 1px 3px rgba(0,0,0,0.1)' }}>
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
