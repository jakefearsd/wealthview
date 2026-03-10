import { BarChart, Bar, XAxis, YAxis, Tooltip, ResponsiveContainer, Legend } from 'recharts';
import { formatCurrency } from '../utils/format';
import { cardStyle } from '../utils/styles';
import PropertyTransactionForm from './PropertyTransactionForm';
import type { MonthlyCashFlowEntry } from '../types/property';

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

interface PropertyCashFlowSectionProps {
    cashFlow: MonthlyCashFlowEntry[] | null;
    canWrite: boolean;
    onAddIncome: (data: { date: string; amount: number; category: string; description?: string; frequency?: string }) => Promise<void>;
    onAddExpense: (data: { date: string; amount: number; category: string; description?: string; frequency?: string }) => Promise<void>;
}

export default function PropertyCashFlowSection({
    cashFlow,
    canWrite,
    onAddIncome,
    onAddExpense,
}: PropertyCashFlowSectionProps) {
    return (
        <>
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
                        onSubmit={onAddIncome}
                        buttonColor="#2e7d32"
                    />
                    <PropertyTransactionForm
                        title="Add Expense"
                        categories={EXPENSE_CATEGORIES}
                        onSubmit={onAddExpense}
                        buttonColor="#d32f2f"
                    />
                </div>
            )}
        </>
    );
}
