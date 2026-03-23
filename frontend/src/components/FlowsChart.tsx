import {
    BarChart, Bar, XAxis, YAxis, Tooltip, ResponsiveContainer,
    ReferenceLine, Legend, CartesianGrid,
} from 'recharts';
import { formatCurrency } from '../utils/format';
import { formatDollarAxis } from '../utils/chartFormatters';
import type { ProjectionYear } from '../types/projection';

interface FlowsChartProps {
    data: ProjectionYear[];
    retirementYear: number | null;
}

export default function FlowsChart({ data, retirementYear }: FlowsChartProps) {

    const FlowsTooltipContent = ({ active, payload, label }: any) => {
        if (!active || !payload?.length) return null;
        const d = data.find(y => y.year === label);
        const labels: Record<string, string> = {
            contributions: 'Contributions',
            growth: 'Growth',
            withdrawals: 'Withdrawals',
            income_streams_total: 'Income Streams',
        };
        const incomeTotal = d?.income_streams_total ?? 0;
        const withdrawals = d?.withdrawals ?? 0;
        const totalCashFlow = withdrawals + incomeTotal;
        const showTotal = d != null && (withdrawals !== 0 || incomeTotal !== 0);
        return (
            <div style={{ background: '#fff', border: '1px solid #ccc', padding: '0.75rem', borderRadius: 4, fontSize: '0.85rem' }}>
                <div style={{ fontWeight: 600, marginBottom: '0.25rem' }}>
                    {d ? `${label} (age ${d.age})` : label}
                </div>
                {payload.map((p: any) => (
                    <div key={p.name} style={{ color: p.color }}>
                        {labels[p.dataKey] ?? p.name}: {formatCurrency(p.value)}
                    </div>
                ))}
                {showTotal && (
                    <>
                        <hr style={{ margin: '0.5rem 0', border: 'none', borderTop: '1px solid #e0e0e0' }} />
                        <div style={{ fontWeight: 600, color: totalCashFlow === 0 ? '#d32f2f' : '#555' }}>
                            Total Cash Flow: {formatCurrency(totalCashFlow)}
                        </div>
                    </>
                )}
            </div>
        );
    };

    return (
        <ResponsiveContainer width="100%" height={450}>
            <BarChart data={data} margin={{ top: 5, right: 20, bottom: 5, left: 20 }}>
                <CartesianGrid strokeDasharray="3 3" stroke="#f0f0f0" />
                <XAxis dataKey="year" tick={{ fontSize: 12 }} />
                <YAxis tickFormatter={formatDollarAxis} tick={{ fontSize: 12 }} width={70} />
                <Tooltip content={<FlowsTooltipContent />} />
                <Legend />
                {retirementYear && <ReferenceLine x={retirementYear} stroke="#ff9800" strokeDasharray="5 5" label="Retire" />}
                <Bar dataKey="contributions" fill="#2e7d32" name="Contributions" />
                <Bar dataKey="growth" fill="#1976d2" name="Growth" />
                <Bar dataKey="withdrawals" stackId="cashflow" fill="#d32f2f" name="Withdrawals" />
                <Bar dataKey="income_streams_total" stackId="cashflow" fill="#7b1fa2" name="Income Streams" />
            </BarChart>
        </ResponsiveContainer>
    );
}
