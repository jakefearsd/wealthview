import { useMemo, useState } from 'react';
import { useParams, Link } from 'react-router-dom';
import { getProperty, addPropertyIncome, addPropertyExpense, getCashFlow, getValuationHistory, refreshValuation } from '../api/properties';
import { useApiQuery } from '../hooks/useApiQuery';
import { useAuth } from '../context/AuthContext';
import { BarChart, Bar, LineChart, Line, XAxis, YAxis, Tooltip, ResponsiveContainer, Legend } from 'recharts';
import { formatCurrency } from '../utils/format';
import { cardStyle } from '../utils/styles';
import PropertyTransactionForm from '../components/PropertyTransactionForm';
import toast from 'react-hot-toast';

const INCOME_CATEGORIES = [
    { value: 'rent', label: 'Rent' },
    { value: 'other', label: 'Other' },
];

const EXPENSE_CATEGORIES = [
    { value: 'mortgage', label: 'Mortgage' },
    { value: 'tax', label: 'Tax' },
    { value: 'insurance', label: 'Insurance' },
    { value: 'maintenance', label: 'Maintenance' },
    { value: 'capex', label: 'CapEx' },
    { value: 'hoa', label: 'HOA' },
    { value: 'mgmt_fee', label: 'Management Fee' },
];

function getDefaultRange() {
    const now = new Date();
    const from = new Date(now.getFullYear() - 1, now.getMonth(), 1);
    return {
        from: `${from.getFullYear()}-${String(from.getMonth() + 1).padStart(2, '0')}`,
        to: `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}`,
    };
}

export default function PropertyDetailPage() {
    const { id } = useParams<{ id: string }>();
    const { role } = useAuth();
    const canWrite = role === 'admin' || role === 'member';
    const range = useMemo(getDefaultRange, []);
    const [refreshing, setRefreshing] = useState(false);

    const { data: property, refetch: refetchProperty } = useApiQuery(() => getProperty(id!));
    const { data: cashFlow, refetch: refetchCashFlow } = useApiQuery(() => getCashFlow(id!, range.from, range.to));
    const { data: valuations, refetch: refetchValuations } = useApiQuery(() => getValuationHistory(id!));

    async function handleAddIncome(data: { date: string; amount: number; category: string; description?: string }) {
        try {
            await addPropertyIncome(id!, data);
            toast.success('Income added');
            refetchCashFlow();
        } catch {
            toast.error('Failed to add income');
        }
    }

    async function handleAddExpense(data: { date: string; amount: number; category: string; description?: string }) {
        try {
            await addPropertyExpense(id!, data);
            toast.success('Expense added');
            refetchCashFlow();
        } catch {
            toast.error('Failed to add expense');
        }
    }

    async function handleRefreshValuation() {
        setRefreshing(true);
        try {
            await refreshValuation(id!);
            toast.success('Valuation refresh requested');
            refetchValuations();
            refetchProperty();
        } catch {
            toast.error('Failed to refresh valuation');
        } finally {
            setRefreshing(false);
        }
    }

    const valuationChartData = useMemo(() => {
        if (!valuations) return [];
        return [...valuations].reverse().map((v) => ({
            date: v.valuation_date,
            value: v.value,
            source: v.source,
        }));
    }, [valuations]);

    const badgeStyle = (color: string, bg: string) => ({
        display: 'inline-block',
        padding: '0.15rem 0.5rem',
        background: bg,
        color: color,
        borderRadius: '4px',
        fontSize: '0.75rem',
        fontWeight: 600 as const,
    });

    return (
        <div>
            <div style={{ marginBottom: '1.5rem' }}>
                <Link to="/properties" style={{ color: '#1976d2', textDecoration: 'none' }}>Properties</Link> / {property?.address}
            </div>

            {property && (
                <div style={{ ...cardStyle, marginBottom: '2rem' }}>
                    <h2 style={{ marginBottom: '1rem' }}>{property.address}</h2>
                    <div style={{ display: 'grid', gridTemplateColumns: 'repeat(4, 1fr)', gap: '1rem' }}>
                        <div><div style={{ color: '#666', fontSize: '0.85rem' }}>Purchase Price</div><div style={{ fontWeight: 600 }}>{formatCurrency(property.purchase_price)}</div></div>
                        <div><div style={{ color: '#666', fontSize: '0.85rem' }}>Current Value</div><div style={{ fontWeight: 600 }}>{formatCurrency(property.current_value)}</div></div>
                        <div>
                            <div style={{ color: '#666', fontSize: '0.85rem' }}>
                                Mortgage{' '}
                                {property.use_computed_balance
                                    ? <span style={badgeStyle('#1565c0', '#e3f2fd')}>Computed</span>
                                    : <span style={badgeStyle('#666', '#eee')}>Manual</span>}
                            </div>
                            <div style={{ fontWeight: 600 }}>{formatCurrency(property.mortgage_balance)}</div>
                        </div>
                        <div><div style={{ color: '#666', fontSize: '0.85rem' }}>Equity</div><div style={{ fontWeight: 600, color: '#2e7d32' }}>{formatCurrency(property.equity)}</div></div>
                    </div>

                    {property.has_loan_details && (
                        <div style={{ marginTop: '1.5rem', padding: '1rem', background: '#f5f5f5', borderRadius: '8px' }}>
                            <h4 style={{ marginBottom: '0.5rem', fontSize: '0.9rem', color: '#444' }}>Loan Details</h4>
                            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(4, 1fr)', gap: '1rem', fontSize: '0.9rem' }}>
                                <div><span style={{ color: '#666' }}>Amount:</span> {formatCurrency(property.loan_amount!)}</div>
                                <div><span style={{ color: '#666' }}>Rate:</span> {property.annual_interest_rate}%</div>
                                <div><span style={{ color: '#666' }}>Term:</span> {property.loan_term_months} months</div>
                                <div><span style={{ color: '#666' }}>Start:</span> {property.loan_start_date}</div>
                            </div>
                        </div>
                    )}
                </div>
            )}

            {valuationChartData.length > 0 && (
                <div style={{ ...cardStyle, marginBottom: '2rem' }}>
                    <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '1rem' }}>
                        <h3>Valuation History</h3>
                        {canWrite && (
                            <button
                                onClick={handleRefreshValuation}
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
                        onClick={handleRefreshValuation}
                        disabled={refreshing}
                        style={{ marginTop: '0.5rem', padding: '0.4rem 0.8rem', background: refreshing ? '#ccc' : '#1976d2', color: '#fff', border: 'none', borderRadius: '4px', cursor: refreshing ? 'default' : 'pointer', fontSize: '0.85rem' }}
                    >
                        {refreshing ? 'Refreshing...' : 'Refresh Valuation'}
                    </button>
                </div>
            )}

            {cashFlow && cashFlow.length > 0 && (
                <div style={{ ...cardStyle, marginBottom: '2rem' }}>
                    <h3 style={{ marginBottom: '1rem' }}>Cash Flow</h3>
                    <ResponsiveContainer width="100%" height={300}>
                        <BarChart data={cashFlow}>
                            <XAxis dataKey="month" />
                            <YAxis />
                            <Tooltip formatter={(value: number) => formatCurrency(value)} />
                            <Legend />
                            <Bar dataKey="total_income" name="Income" fill="#2e7d32" />
                            <Bar dataKey="total_expenses" name="Expenses" fill="#d32f2f" />
                        </BarChart>
                    </ResponsiveContainer>
                </div>
            )}

            {canWrite && (
                <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '1.5rem' }}>
                    <PropertyTransactionForm
                        title="Add Income"
                        categories={INCOME_CATEGORIES}
                        onSubmit={handleAddIncome}
                        buttonColor="#2e7d32"
                    />
                    <PropertyTransactionForm
                        title="Add Expense"
                        categories={EXPENSE_CATEGORIES}
                        onSubmit={handleAddExpense}
                        buttonColor="#d32f2f"
                    />
                </div>
            )}
        </div>
    );
}
