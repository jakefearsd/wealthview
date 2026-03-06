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
    mode: 'balance' | 'flows' | 'spending';
}

export default function ProjectionChart({ data, retirementYear, mode }: ProjectionChartProps) {
    const tickFormatter = (v: number) =>
        Math.abs(v) >= 1000000 ? `$${(v / 1000000).toFixed(1)}M` : `$${(v / 1000).toFixed(0)}k`;

    if (mode === 'spending') {
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
    const hasPoolData = data.some(d => d.traditional_balance !== null);

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
                {!hasPoolData && (
                    <Area
                        type="monotone"
                        dataKey="cumulative_contributions"
                        stroke="#81c784"
                        strokeWidth={1}
                        fill="url(#colorContributions)"
                        name="Total Contributions"
                    />
                )}
                {!hasPoolData && (
                    <Area
                        type="monotone"
                        dataKey="end_balance"
                        stroke="#1976d2"
                        strokeWidth={2}
                        fill="url(#colorBalance)"
                        name="Balance"
                    />
                )}
                {hasPoolData && (
                    <>
                        <Area type="monotone" dataKey="traditional_balance" stackId="pools" stroke="#e65100" fill="#e65100" fillOpacity={0.3} name="Traditional" />
                        <Area type="monotone" dataKey="roth_balance" stackId="pools" stroke="#2e7d32" fill="#2e7d32" fillOpacity={0.3} name="Roth" />
                        <Area type="monotone" dataKey="taxable_balance" stackId="pools" stroke="#1976d2" fill="#1976d2" fillOpacity={0.3} name="Taxable" />
                    </>
                )}
            </AreaChart>
        </ResponsiveContainer>
    );
}
