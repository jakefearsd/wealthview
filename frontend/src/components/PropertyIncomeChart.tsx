import { useEffect, useState } from 'react';
import {
    BarChart, Bar, XAxis, YAxis, Tooltip, ResponsiveContainer, Legend,
    ReferenceLine, CartesianGrid,
} from 'recharts';
import { getCashFlowDetail } from '../api/properties';
import { formatCurrency } from '../utils/format';
import { cardStyle } from '../utils/styles';
import type { MonthlyCashFlowDetailEntry } from '../types/property';

const CATEGORY_CONFIG: Record<string, { label: string; color: string }> = {
    mortgage: { label: 'Mortgage', color: '#1976d2' },
    tax: { label: 'Tax', color: '#ed6c02' },
    insurance: { label: 'Insurance', color: '#9c27b0' },
    maintenance: { label: 'Maintenance', color: '#0097a7' },
    hoa: { label: 'HOA', color: '#795548' },
    capex: { label: 'CapEx', color: '#3f51b5' },
    mgmt_fee: { label: 'Mgmt Fee', color: '#607d8b' },
};

interface PropertyIncomeChartProps {
    propertyId: string;
    propertyAddress: string;
    monthlyRentEstimate: number;
}

interface ChartRow {
    month: string;
    label: string;
    income: number;
    [category: string]: number | string;
}

function buildChartData(entries: MonthlyCashFlowDetailEntry[], monthlyRent: number): ChartRow[] {
    return entries.map(entry => {
        const row: ChartRow = {
            month: entry.month,
            label: formatMonthLabel(entry.month),
            income: monthlyRent,
        };
        for (const [cat, amount] of Object.entries(entry.expenses_by_category)) {
            row[cat] = -amount;
        }
        return row;
    });
}

function formatMonthLabel(month: string): string {
    const [year, m] = month.split('-');
    const names = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
    return `${names[parseInt(m) - 1]} '${year.slice(2)}`;
}

function collectCategories(entries: MonthlyCashFlowDetailEntry[]): string[] {
    const cats = new Set<string>();
    for (const entry of entries) {
        for (const cat of Object.keys(entry.expenses_by_category)) {
            cats.add(cat);
        }
    }
    const order = Object.keys(CATEGORY_CONFIG);
    return [...cats].sort((a, b) => order.indexOf(a) - order.indexOf(b));
}

function CustomTooltip({ active, payload, label }: { active?: boolean; payload?: Array<{ name: string; value: number; color: string }>; label?: string }) {
    if (!active || !payload) return null;

    const income = payload.find(p => p.name === 'Rent Estimate');
    const expenses = payload.filter(p => p.name !== 'Rent Estimate');
    const totalExpenses = expenses.reduce((sum, p) => sum + Math.abs(p.value), 0);
    const net = (income?.value ?? 0) - totalExpenses;

    return (
        <div style={{ background: '#fff', border: '1px solid #ddd', borderRadius: 6, padding: '0.75rem', fontSize: '0.85rem' }}>
            <div style={{ fontWeight: 600, marginBottom: '0.5rem' }}>{label}</div>
            {income && (
                <div style={{ color: '#2e7d32', marginBottom: '0.25rem' }}>
                    Rent Estimate: {formatCurrency(income.value)}
                </div>
            )}
            {expenses.map(p => (
                <div key={p.name} style={{ color: p.color, marginBottom: '0.15rem' }}>
                    {p.name}: {formatCurrency(Math.abs(p.value))}
                </div>
            ))}
            {expenses.length > 0 && (
                <>
                    <div style={{ borderTop: '1px solid #eee', marginTop: '0.25rem', paddingTop: '0.25rem', color: '#d32f2f' }}>
                        Total Expenses: {formatCurrency(totalExpenses)}
                    </div>
                    <div style={{ fontWeight: 600, color: net >= 0 ? '#2e7d32' : '#d32f2f' }}>
                        Net: {formatCurrency(net)}
                    </div>
                </>
            )}
        </div>
    );
}

export default function PropertyIncomeChart({ propertyId, propertyAddress, monthlyRentEstimate }: PropertyIncomeChartProps) {
    const [data, setData] = useState<MonthlyCashFlowDetailEntry[] | null>(null);
    const [loading, setLoading] = useState(true);

    useEffect(() => {
        const now = new Date();
        const from = `${now.getFullYear() - 1}-${String(now.getMonth() + 2).padStart(2, '0')}`;
        const to = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}`;
        getCashFlowDetail(propertyId, from, to)
            .then(setData)
            .catch(() => setData(null))
            .finally(() => setLoading(false));
    }, [propertyId]);

    if (loading) {
        return <div style={{ ...cardStyle, textAlign: 'center', padding: '1.5rem', color: '#999' }}>Loading property data...</div>;
    }

    if (!data || data.length === 0) {
        return (
            <div style={{ ...cardStyle, textAlign: 'center', padding: '1.5rem', color: '#999', fontSize: '0.9rem' }}>
                No income or expense data logged on this property yet.
                <br />
                <span style={{ fontSize: '0.8rem' }}>Add records on the <a href={`/properties`} style={{ color: '#1976d2' }}>property detail page</a> to see the breakdown here.</span>
            </div>
        );
    }

    const categories = collectCategories(data);
    const chartData = buildChartData(data, monthlyRentEstimate);

    const maxExpense = Math.max(...data.map(d => d.total_expenses));
    const yMin = -Math.ceil(maxExpense / 500) * 500 - 500;
    const yMax = Math.ceil(monthlyRentEstimate / 500) * 500 + 500;

    return (
        <div style={{ ...cardStyle, marginTop: '0.75rem' }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', marginBottom: '0.5rem' }}>
                <div>
                    <h4 style={{ margin: 0, fontSize: '0.95rem' }}>Rent vs Expenses — {propertyAddress}</h4>
                    <div style={{ fontSize: '0.8rem', color: '#888', marginTop: '0.25rem' }}>
                        Trailing 12 months. Rent estimate: {formatCurrency(monthlyRentEstimate)}/mo
                    </div>
                </div>
            </div>
            <ResponsiveContainer width="100%" height={300}>
                <BarChart data={chartData} stackOffset="sign" margin={{ top: 5, right: 20, bottom: 5, left: 20 }}>
                    <CartesianGrid strokeDasharray="3 3" stroke="#f0f0f0" />
                    <XAxis dataKey="label" tick={{ fontSize: 11 }} />
                    <YAxis
                        domain={[yMin, yMax]}
                        tick={{ fontSize: 11 }}
                        tickFormatter={(v: number) => `$${Math.abs(v / 1000).toFixed(1)}k`}
                    />
                    <Tooltip content={<CustomTooltip />} />
                    <Legend />
                    <ReferenceLine y={0} stroke="#999" strokeWidth={1} />
                    <Bar dataKey="income" name="Rent Estimate" fill="#2e7d32" stackId="pos" />
                    {categories.map(cat => (
                        <Bar
                            key={cat}
                            dataKey={cat}
                            name={CATEGORY_CONFIG[cat]?.label ?? cat}
                            fill={CATEGORY_CONFIG[cat]?.color ?? '#999'}
                            stackId="neg"
                        />
                    ))}
                </BarChart>
            </ResponsiveContainer>
        </div>
    );
}
