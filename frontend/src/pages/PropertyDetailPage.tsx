import { useMemo } from 'react';
import { useParams, Link } from 'react-router-dom';
import { getProperty, addPropertyIncome, addPropertyExpense, getCashFlow } from '../api/properties';
import { useApiQuery } from '../hooks/useApiQuery';
import { useAuth } from '../context/AuthContext';
import { BarChart, Bar, XAxis, YAxis, Tooltip, ResponsiveContainer, Legend } from 'recharts';
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

    const { data: property } = useApiQuery(() => getProperty(id!));
    const { data: cashFlow, refetch: refetchCashFlow } = useApiQuery(() => getCashFlow(id!, range.from, range.to));

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
                        <div><div style={{ color: '#666', fontSize: '0.85rem' }}>Mortgage</div><div style={{ fontWeight: 600 }}>{formatCurrency(property.mortgage_balance)}</div></div>
                        <div><div style={{ color: '#666', fontSize: '0.85rem' }}>Equity</div><div style={{ fontWeight: 600, color: '#2e7d32' }}>{formatCurrency(property.equity)}</div></div>
                    </div>
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
