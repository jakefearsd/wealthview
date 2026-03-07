import { useMemo, useState } from 'react';
import { useParams, Link } from 'react-router-dom';
import { getProperty, addPropertyIncome, addPropertyExpense, getCashFlow, getValuationHistory, refreshValuation, getPropertyAnalytics } from '../api/properties';
import { useApiQuery } from '../hooks/useApiQuery';
import { useAuth } from '../context/AuthContext';
import { BarChart, Bar, LineChart, Line, XAxis, YAxis, Tooltip, ResponsiveContainer, Legend } from 'recharts';
import { formatCurrency } from '../utils/format';
import { cardStyle } from '../utils/styles';
import PropertyTransactionForm from '../components/PropertyTransactionForm';
import HelpText from '../components/HelpText';
import InfoSection from '../components/InfoSection';
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
    const { data: analytics } = useApiQuery(() => getPropertyAnalytics(id!));

    async function handleAddIncome(data: { date: string; amount: number; category: string; description?: string; frequency?: string }) {
        try {
            await addPropertyIncome(id!, data);
            toast.success('Income added');
            refetchCashFlow();
        } catch {
            toast.error('Failed to add income');
        }
    }

    async function handleAddExpense(data: { date: string; amount: number; category: string; description?: string; frequency?: string }) {
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
                    <div style={{ display: 'flex', alignItems: 'center', gap: '0.75rem', marginBottom: '1rem' }}>
                        <h2>{property.address}</h2>
                        <span style={badgeStyle(
                            property.property_type === 'investment' ? '#e65100' : property.property_type === 'vacation' ? '#1b5e20' : '#1565c0',
                            property.property_type === 'investment' ? '#fff3e0' : property.property_type === 'vacation' ? '#e8f5e9' : '#e3f2fd'
                        )}>
                            {property.property_type === 'primary_residence' ? 'Primary Residence' : property.property_type === 'investment' ? 'Investment' : 'Vacation'}
                        </span>
                    </div>
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

            {analytics && (
                <div style={{ ...cardStyle, marginBottom: '2rem' }}>
                    <h3 style={{ marginBottom: '1rem' }}>Property Overview</h3>
                    <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)', gap: '1rem', marginBottom: '1.5rem' }}>
                        <div>
                            <div style={{ color: '#666', fontSize: '0.85rem' }}>Total Appreciation</div>
                            <div style={{ fontWeight: 600, color: analytics.total_appreciation >= 0 ? '#2e7d32' : '#d32f2f' }}>
                                {formatCurrency(analytics.total_appreciation)}
                            </div>
                        </div>
                        <div>
                            <div style={{ color: '#666', fontSize: '0.85rem' }}>Appreciation %</div>
                            <div style={{ fontWeight: 600, color: analytics.appreciation_percent >= 0 ? '#2e7d32' : '#d32f2f' }}>
                                {analytics.appreciation_percent.toFixed(2)}%
                            </div>
                        </div>
                        {analytics.mortgage_progress && (
                            <div>
                                <div style={{ color: '#666', fontSize: '0.85rem' }}>Months Remaining</div>
                                <div style={{ fontWeight: 600 }}>{analytics.mortgage_progress.months_remaining}</div>
                            </div>
                        )}
                    </div>

                    {analytics.mortgage_progress && (
                        <div style={{ marginBottom: '1.5rem', padding: '1rem', background: '#f5f5f5', borderRadius: '8px' }}>
                            <h4 style={{ marginBottom: '0.75rem', fontSize: '0.9rem', color: '#444' }}>Mortgage Payoff Progress</h4>
                            <div style={{ background: '#e0e0e0', borderRadius: '4px', height: '24px', marginBottom: '0.75rem', position: 'relative' as const }}>
                                <div style={{
                                    background: '#1976d2',
                                    height: '100%',
                                    borderRadius: '4px',
                                    width: `${Math.min(analytics.mortgage_progress.percent_paid_off, 100)}%`,
                                    transition: 'width 0.3s',
                                    display: 'flex',
                                    alignItems: 'center',
                                    justifyContent: 'center',
                                    color: '#fff',
                                    fontSize: '0.75rem',
                                    fontWeight: 600,
                                }}>
                                    {analytics.mortgage_progress.percent_paid_off.toFixed(1)}%
                                </div>
                            </div>
                            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(4, 1fr)', gap: '1rem', fontSize: '0.85rem' }}>
                                <div><span style={{ color: '#666' }}>Principal Paid:</span> {formatCurrency(analytics.mortgage_progress.principal_paid)}</div>
                                <div><span style={{ color: '#666' }}>Balance:</span> {formatCurrency(analytics.mortgage_progress.current_balance)}</div>
                                <div><span style={{ color: '#666' }}>Payoff Date:</span> {analytics.mortgage_progress.estimated_payoff_date}</div>
                                <div><span style={{ color: '#666' }}>Remaining:</span> {analytics.mortgage_progress.months_remaining} months</div>
                            </div>
                        </div>
                    )}

                    {analytics.equity_growth.length > 0 && (
                        <div>
                            <h4 style={{ marginBottom: '0.75rem', fontSize: '0.9rem', color: '#444' }}>Equity Growth</h4>
                            <ResponsiveContainer width="100%" height={300}>
                                <LineChart data={analytics.equity_growth}>
                                    <XAxis dataKey="month" />
                                    <YAxis />
                                    <Tooltip formatter={(value: number) => formatCurrency(value)} />
                                    <Legend />
                                    <Line type="monotone" dataKey="equity" name="Equity" stroke="#2e7d32" strokeWidth={2} dot={false} />
                                    <Line type="monotone" dataKey="property_value" name="Property Value" stroke="#1976d2" strokeWidth={1} strokeDasharray="5 5" dot={false} />
                                    <Line type="monotone" dataKey="mortgage_balance" name="Mortgage" stroke="#d32f2f" strokeWidth={1} strokeDasharray="5 5" dot={false} />
                                </LineChart>
                            </ResponsiveContainer>
                        </div>
                    )}
                </div>
            )}

            {analytics && analytics.property_type === 'investment' && analytics.cap_rate !== null && (
                <div style={{ ...cardStyle, marginBottom: '2rem' }}>
                    <h3 style={{ marginBottom: '0.5rem' }}>Investment Metrics</h3>
                    <InfoSection prompt="How to read these metrics">
                        Cap Rate and NOI measure the property's operating performance independent of financing. Cash-on-Cash Return measures your actual return based on the cash you invested, factoring in leverage from your mortgage. A higher cap rate or cash-on-cash return indicates a stronger investment.
                    </InfoSection>
                    <div style={{ display: 'grid', gridTemplateColumns: 'repeat(5, 1fr)', gap: '1rem' }}>
                        <div>
                            <div style={{ color: '#666', fontSize: '0.85rem' }}>Cap Rate</div>
                            <div style={{ fontWeight: 600, fontSize: '1.2rem' }}>{analytics.cap_rate!.toFixed(2)}%</div>
                            <HelpText>Annual NOI ÷ property value. Measures return independent of financing.</HelpText>
                        </div>
                        <div>
                            <div style={{ color: '#666', fontSize: '0.85rem' }}>Cash-on-Cash Return</div>
                            <div style={{ fontWeight: 600, fontSize: '1.2rem', color: analytics.cash_on_cash_return! >= 0 ? '#2e7d32' : '#d32f2f' }}>
                                {analytics.cash_on_cash_return!.toFixed(2)}%
                            </div>
                            <HelpText>Annual net cash flow ÷ cash invested. Your actual return accounting for leverage.</HelpText>
                        </div>
                        <div>
                            <div style={{ color: '#666', fontSize: '0.85rem' }}>Annual NOI</div>
                            <div style={{ fontWeight: 600 }}>{formatCurrency(analytics.annual_noi!)}</div>
                            <HelpText>Net Operating Income: rental income minus operating expenses, excluding mortgage.</HelpText>
                        </div>
                        <div>
                            <div style={{ color: '#666', fontSize: '0.85rem' }}>Net Cash Flow</div>
                            <div style={{ fontWeight: 600, color: analytics.annual_net_cash_flow! >= 0 ? '#2e7d32' : '#d32f2f' }}>
                                {formatCurrency(analytics.annual_net_cash_flow!)}
                            </div>
                            <HelpText>Cash remaining after all expenses including mortgage payments.</HelpText>
                        </div>
                        <div>
                            <div style={{ color: '#666', fontSize: '0.85rem' }}>Cash Invested</div>
                            <div style={{ fontWeight: 600 }}>{formatCurrency(analytics.total_cash_invested!)}</div>
                            <HelpText>Your out-of-pocket investment: purchase price minus loan amount.</HelpText>
                        </div>
                    </div>
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
