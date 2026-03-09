import { useMemo } from 'react';
import {
    AreaChart, Area, BarChart, Bar, XAxis, YAxis, Tooltip, ResponsiveContainer,
    ReferenceLine, ReferenceArea, Legend, CartesianGrid,
} from 'recharts';
import { formatCurrency } from '../utils/format';
import { findDepletionYear, findCrossoverYear } from '../utils/projectionCalcs';
import { interpolateMonthly } from '../utils/monthlyInterpolation';
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
                    <YAxis tickFormatter={tickFormatter} tick={{ fontSize: 12 }} width={70} />
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

    const monthlyData = useMemo(() => interpolateMonthly(data), [data]);
    const depletion = findDepletionYear(data);
    const hasPoolData = data.some(d => d.traditional_balance !== null);
    const hasSpendingProfile = data.some(d => d.essential_expenses != null);
    const crossover = hasSpendingProfile ? findCrossoverYear(data) : null;

    // For single-pool mode, compute cumulative contributions at monthly granularity
    const monthlyChartData = useMemo(() => {
        if (hasPoolData) return monthlyData;
        let cumContrib = 0;
        let yearIdx = 0;
        return monthlyData.map((pt) => {
            // Find the matching annual row to get contributions
            while (yearIdx < data.length - 1 && data[yearIdx].year < pt.year) yearIdx++;
            const annualRow = data[yearIdx];
            const monthlyContrib = annualRow ? annualRow.contributions / 12 : 0;
            cumContrib += pt.month === 1 && pt.year === data[0]?.year ? 0 : monthlyContrib;
            if (pt.month === 1 && pt.year === data[0]?.year) {
                // First month: no contributions yet
            }
            return { ...pt, cumulative_contributions: cumContrib };
        });
    }, [monthlyData, data, hasPoolData]);

    // Compute XAxis tick interval: show ~one tick per year
    const xTickInterval = Math.max(1, Math.floor(monthlyData.length / Math.min(data.length, 30)));

    const monthNames = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

    const xTickFormatter = (label: string) => {
        const year = label.substring(0, 4);
        const month = parseInt(label.substring(5, 7), 10);
        if (month === 1) return year;
        return '';
    };

    const retireLabel = retirementYear ? `${retirementYear}-01` : null;
    const depletionLabel = depletion ? `${depletion.year}-01` : null;
    const crossoverLabel = crossover ? `${crossover.year}-01` : null;
    const lastLabel = monthlyData.length > 0 ? monthlyData[monthlyData.length - 1].label : null;

    const BalanceTooltipContent = ({ active, payload, label }: any) => {
        if (!active || !payload?.length) return null;
        const pt = monthlyData.find(d => d.label === label);
        if (!pt) return null;
        const monthName = monthNames[pt.month - 1];
        const annualRow = data.find(d => d.year === pt.year);
        return (
            <div style={{ background: '#fff', border: '1px solid #ccc', padding: '0.75rem', borderRadius: 4, fontSize: '0.85rem' }}>
                <div style={{ fontWeight: 600, marginBottom: '0.25rem' }}>
                    {monthName} {pt.year} (age {pt.age})
                </div>
                {payload.map((p: any) => (
                    <div key={p.name} style={{ color: p.color }}>
                        {p.name}: {formatCurrency(p.value)}
                    </div>
                ))}
                {annualRow?.retired && annualRow.essential_expenses != null && pt.month === 12 && (
                    <>
                        <hr style={{ margin: '0.5rem 0', border: 'none', borderTop: '1px solid #e0e0e0' }} />
                        <div style={{ color: '#555' }}>Spending Need: {formatCurrency(annualRow.net_spending_need!)}</div>
                        <div style={{ color: '#555' }}>Growth: {formatCurrency(annualRow.growth)}</div>
                        {annualRow.withdrawals > annualRow.growth && (
                            <div style={{ color: '#d32f2f', fontWeight: 600 }}>
                                Annual Drain: {formatCurrency(annualRow.growth - annualRow.withdrawals)}
                            </div>
                        )}
                    </>
                )}
            </div>
        );
    };

    return (
        <ResponsiveContainer width="100%" height={450}>
            <AreaChart data={monthlyChartData} margin={{ top: 5, right: 20, bottom: 5, left: 20 }}>
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
                <XAxis
                    dataKey="label"
                    tick={{ fontSize: 12 }}
                    tickFormatter={xTickFormatter}
                    interval={xTickInterval}
                />
                <YAxis tickFormatter={tickFormatter} tick={{ fontSize: 12 }} width={70} />
                <Tooltip content={<BalanceTooltipContent />} />
                <Legend />
                {retireLabel && <ReferenceLine x={retireLabel} stroke="#ff9800" strokeDasharray="5 5" label="Retire" />}
                {depletionLabel && <ReferenceLine x={depletionLabel} stroke="#d32f2f" strokeDasharray="5 5" label="Depleted" />}
                {crossoverLabel && (
                    <ReferenceArea
                        x1={crossoverLabel}
                        x2={depletionLabel ?? lastLabel ?? crossoverLabel}
                        fill="#d32f2f"
                        fillOpacity={0.06}
                        strokeOpacity={0}
                    />
                )}
                {crossoverLabel && (
                    <ReferenceLine
                        x={crossoverLabel}
                        stroke="#e65100"
                        strokeDasharray="3 3"
                        label={{ value: `Spending > Growth (age ${crossover!.age})`, position: "insideTopLeft", fontSize: 11, fill: "#e65100" }}
                    />
                )}
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
                        dataKey="balance"
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
