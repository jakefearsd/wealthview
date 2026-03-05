import { useState, useMemo } from 'react';
import { useParams, Link } from 'react-router-dom';
import { getProperty, addPropertyIncome, addPropertyExpense, getCashFlow } from '../api/properties';
import { useApiQuery } from '../hooks/useApiQuery';
import { useAuth } from '../context/AuthContext';
import { BarChart, Bar, XAxis, YAxis, Tooltip, ResponsiveContainer, Legend } from 'recharts';
import toast from 'react-hot-toast';

function formatCurrency(value: number): string {
    return new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD' }).format(value);
}

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

    const [incomeDate, setIncomeDate] = useState('');
    const [incomeAmount, setIncomeAmount] = useState('');
    const [incomeCategory, setIncomeCategory] = useState('rent');
    const [incomeDesc, setIncomeDesc] = useState('');

    const [expenseDate, setExpenseDate] = useState('');
    const [expenseAmount, setExpenseAmount] = useState('');
    const [expenseCategory, setExpenseCategory] = useState('mortgage');
    const [expenseDesc, setExpenseDesc] = useState('');

    async function handleAddIncome() {
        try {
            await addPropertyIncome(id!, {
                date: incomeDate,
                amount: parseFloat(incomeAmount),
                category: incomeCategory,
                description: incomeDesc || undefined,
            });
            toast.success('Income added');
            setIncomeDate('');
            setIncomeAmount('');
            setIncomeDesc('');
            refetchCashFlow();
        } catch {
            toast.error('Failed to add income');
        }
    }

    async function handleAddExpense() {
        try {
            await addPropertyExpense(id!, {
                date: expenseDate,
                amount: parseFloat(expenseAmount),
                category: expenseCategory,
                description: expenseDesc || undefined,
            });
            toast.success('Expense added');
            setExpenseDate('');
            setExpenseAmount('');
            setExpenseDesc('');
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
                <div style={{ background: '#fff', padding: '1.5rem', borderRadius: '8px', marginBottom: '2rem', boxShadow: '0 1px 3px rgba(0,0,0,0.1)' }}>
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
                <div style={{ background: '#fff', padding: '1.5rem', borderRadius: '8px', marginBottom: '2rem', boxShadow: '0 1px 3px rgba(0,0,0,0.1)' }}>
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
                    <div style={{ background: '#fff', padding: '1.5rem', borderRadius: '8px', boxShadow: '0 1px 3px rgba(0,0,0,0.1)' }}>
                        <h3 style={{ marginBottom: '1rem' }}>Add Income</h3>
                        <div style={{ display: 'grid', gap: '0.5rem' }}>
                            <input type="date" value={incomeDate} onChange={(e) => setIncomeDate(e.target.value)} style={{ padding: '0.4rem' }} />
                            <input type="number" step="0.01" placeholder="Amount" value={incomeAmount} onChange={(e) => setIncomeAmount(e.target.value)} style={{ padding: '0.4rem' }} />
                            <select value={incomeCategory} onChange={(e) => setIncomeCategory(e.target.value)} style={{ padding: '0.4rem' }}>
                                <option value="rent">Rent</option>
                                <option value="other">Other</option>
                            </select>
                            <input placeholder="Description" value={incomeDesc} onChange={(e) => setIncomeDesc(e.target.value)} style={{ padding: '0.4rem' }} />
                            <button onClick={handleAddIncome} style={{ padding: '0.5rem', background: '#2e7d32', color: '#fff', border: 'none', borderRadius: '4px', cursor: 'pointer' }}>Add Income</button>
                        </div>
                    </div>
                    <div style={{ background: '#fff', padding: '1.5rem', borderRadius: '8px', boxShadow: '0 1px 3px rgba(0,0,0,0.1)' }}>
                        <h3 style={{ marginBottom: '1rem' }}>Add Expense</h3>
                        <div style={{ display: 'grid', gap: '0.5rem' }}>
                            <input type="date" value={expenseDate} onChange={(e) => setExpenseDate(e.target.value)} style={{ padding: '0.4rem' }} />
                            <input type="number" step="0.01" placeholder="Amount" value={expenseAmount} onChange={(e) => setExpenseAmount(e.target.value)} style={{ padding: '0.4rem' }} />
                            <select value={expenseCategory} onChange={(e) => setExpenseCategory(e.target.value)} style={{ padding: '0.4rem' }}>
                                <option value="mortgage">Mortgage</option>
                                <option value="tax">Tax</option>
                                <option value="insurance">Insurance</option>
                                <option value="maintenance">Maintenance</option>
                                <option value="capex">CapEx</option>
                                <option value="hoa">HOA</option>
                                <option value="mgmt_fee">Management Fee</option>
                            </select>
                            <input placeholder="Description" value={expenseDesc} onChange={(e) => setExpenseDesc(e.target.value)} style={{ padding: '0.4rem' }} />
                            <button onClick={handleAddExpense} style={{ padding: '0.5rem', background: '#d32f2f', color: '#fff', border: 'none', borderRadius: '4px', cursor: 'pointer' }}>Add Expense</button>
                        </div>
                    </div>
                </div>
            )}
        </div>
    );
}
