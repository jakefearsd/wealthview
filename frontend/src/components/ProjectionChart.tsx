import {
    AreaChart, Area, BarChart, Bar, XAxis, YAxis, Tooltip, ResponsiveContainer,
    ReferenceLine, Legend, CartesianGrid,
} from 'recharts';
import { formatCurrency } from '../utils/format';
import { computeCumulativeContributions, findDepletionYear } from '../utils/projectionCalcs';
import type { ProjectionYear } from '../types/projection';

interface ProjectionChartProps {
    data: ProjectionYear[];
    retirementYear: number | null;
    mode: 'balance' | 'flows';
}

export default function ProjectionChart({ data, retirementYear, mode }: ProjectionChartProps) {
    const tickFormatter = (v: number) =>
        Math.abs(v) >= 1000000 ? `$${(v / 1000000).toFixed(1)}M` : `$${(v / 1000).toFixed(0)}k`;

    if (mode === 'flows') {
        return (
            <ResponsiveContainer width="100%" height={450}>
                <BarChart data={data} margin={{ top: 5, right: 20, bottom: 5, left: 20 }}>
                    <CartesianGrid strokeDasharray="3 3" stroke="#f0f0f0" />
                    <XAxis dataKey="year" tick={{ fontSize: 12 }} />
                    <YAxis tickFormatter={tickFormatter} tick={{ fontSize: 12 }} width={70} />
                    <Tooltip
                        formatter={(value: number, name: string) => {
                            const labels: Record<string, string> = { contributions: 'Contributions', growth: 'Growth', withdrawals: 'Withdrawals' };
                            return [formatCurrency(value), labels[name] ?? name];
                        }}
                        labelFormatter={(year: number) => {
                            const d = data.find(y => y.year === year);
                            return d ? `${year} (age ${d.age})` : String(year);
                        }}
                    />
                    <Legend />
                    {retirementYear && <ReferenceLine x={retirementYear} stroke="#ff9800" strokeDasharray="5 5" label="Retire" />}
                    <Bar dataKey="contributions" fill="#2e7d32" name="Contributions" />
                    <Bar dataKey="growth" fill="#1976d2" name="Growth" />
                    <Bar dataKey="withdrawals" fill="#d32f2f" name="Withdrawals" />
                </BarChart>
            </ResponsiveContainer>
        );
    }

    const cumulative = computeCumulativeContributions(data);
    const chartData = data.map((row, i) => ({ ...row, cumulative_contributions: cumulative[i] }));
    const depletion = findDepletionYear(data);

    return (
        <ResponsiveContainer width="100%" height={450}>
            <AreaChart data={chartData} margin={{ top: 5, right: 20, bottom: 5, left: 20 }}>
                <defs>
                    <linearGradient id="colorBalance" x1="0" y1="0" x2="0" y2="1">
                        <stop offset="5%" stopColor="#1976d2" stopOpacity={0.3} />
                        <stop offset="95%" stopColor="#1976d2" stopOpacity={0} />
                    </linearGradient>
                    <linearGradient id="colorContributions" x1="0" y1="0" x2="0" y2="1">
                        <stop offset="5%" stopColor="#81c784" stopOpacity={0.2} />
                        <stop offset="95%" stopColor="#81c784" stopOpacity={0} />
                    </linearGradient>
                </defs>
                <CartesianGrid strokeDasharray="3 3" stroke="#f0f0f0" />
                <XAxis dataKey="year" tick={{ fontSize: 12 }} />
                <YAxis tickFormatter={tickFormatter} tick={{ fontSize: 12 }} width={70} />
                <Tooltip
                    formatter={(value: number, name: string) => {
                        const labels: Record<string, string> = {
                            end_balance: 'Balance',
                            cumulative_contributions: 'Total Contributions',
                        };
                        return [formatCurrency(value), labels[name] ?? name];
                    }}
                    labelFormatter={(year: number) => {
                        const d = data.find(y => y.year === year);
                        return d ? `${year} (age ${d.age})` : String(year);
                    }}
                />
                {retirementYear && <ReferenceLine x={retirementYear} stroke="#ff9800" strokeDasharray="5 5" label="Retire" />}
                {depletion && <ReferenceLine x={depletion.year} stroke="#d32f2f" strokeDasharray="5 5" label="Depleted" />}
                <Area
                    type="monotone"
                    dataKey="cumulative_contributions"
                    stroke="#81c784"
                    strokeWidth={1}
                    fill="url(#colorContributions)"
                    name="Total Contributions"
                />
                <Area
                    type="monotone"
                    dataKey="end_balance"
                    stroke="#1976d2"
                    strokeWidth={2}
                    fill="url(#colorBalance)"
                    name="Balance"
                />
            </AreaChart>
        </ResponsiveContainer>
    );
}
