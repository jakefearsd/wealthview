import {
    AreaChart, Area, XAxis, YAxis, Tooltip, ResponsiveContainer,
    Legend, CartesianGrid,
} from 'recharts';
import { formatCurrency } from '../utils/format';
import type { ProjectionYear } from '../types/projection';

interface SpendingChartProps {
    data: ProjectionYear[];
}

export default function SpendingChart({ data }: SpendingChartProps) {
    const tickFormatter = (v: number) =>
        Math.abs(v) >= 1000000 ? `$${(v / 1000000).toFixed(1)}M` : `$${(v / 1000).toFixed(0)}k`;

    const spendingData = data.filter(d => d.essential_expenses != null);

    return (
        <ResponsiveContainer width="100%" height={450}>
            <AreaChart data={spendingData} margin={{ top: 5, right: 20, bottom: 5, left: 20 }}>
                <defs>
                    <linearGradient id="colorEssential" x1="0" y1="0" x2="0" y2="1">
                        <stop offset="5%" stopColor="#ef5350" stopOpacity={0.4} />
                        <stop offset="95%" stopColor="#ef5350" stopOpacity={0.1} />
                    </linearGradient>
                    <linearGradient id="colorDiscretionary" x1="0" y1="0" x2="0" y2="1">
                        <stop offset="5%" stopColor="#ffa726" stopOpacity={0.4} />
                        <stop offset="95%" stopColor="#ffa726" stopOpacity={0.1} />
                    </linearGradient>
                </defs>
                <CartesianGrid strokeDasharray="3 3" stroke="#f0f0f0" />
                <XAxis dataKey="year" tick={{ fontSize: 12 }} />
                <YAxis tickFormatter={tickFormatter} tick={{ fontSize: 12 }} width={70} />
                <Tooltip
                    formatter={(value: number, name: string) => {
                        const labels: Record<string, string> = {
                            essential_expenses: 'Essential Expenses',
                            discretionary_after_cuts: 'Discretionary (After Cuts)',
                            withdrawals: 'Withdrawal',
                            income_streams_total: 'Income Streams',
                        };
                        return [formatCurrency(value), labels[name] ?? name];
                    }}
                    labelFormatter={(year: number) => {
                        const d = data.find(y => y.year === year);
                        return d ? `${year} (age ${d.age})` : String(year);
                    }}
                />
                <Legend />
                <Area type="monotone" dataKey="essential_expenses" stackId="spending" stroke="#ef5350" fill="url(#colorEssential)" name="Essential Expenses" />
                <Area type="monotone" dataKey="discretionary_after_cuts" stackId="spending" stroke="#ffa726" fill="url(#colorDiscretionary)" name="Discretionary (After Cuts)" />
                <Area type="monotone" dataKey="withdrawals" stroke="#1976d2" strokeWidth={2} fill="none" name="Withdrawal" />
                <Area type="monotone" dataKey="income_streams_total" stroke="#2e7d32" strokeWidth={2} fill="none" strokeDasharray="5 5" name="Income Streams" />
            </AreaChart>
        </ResponsiveContainer>
    );
}
