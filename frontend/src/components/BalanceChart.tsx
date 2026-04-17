import { useMemo, useState } from 'react';
import {
    AreaChart, Area, XAxis, YAxis, Tooltip, ResponsiveContainer,
    ReferenceLine, ReferenceArea, Legend, CartesianGrid,
} from 'recharts';
import { formatCurrency } from '../utils/format';
import { formatDollarAxis } from '../utils/chartFormatters';
import { findDepletionYear, findCrossoverYear } from '../utils/projectionCalcs';
import { interpolateMonthly } from '../utils/monthlyInterpolation';
import { tooltipStyle } from '../utils/styles';
import type { ProjectionYear } from '../types/projection';
import type { RechartsTooltipProps } from '../types/recharts';

interface BalanceChartProps {
    data: ProjectionYear[];
    retirementYear: number | null;
}

type TimeRange = 'all' | '5yr' | '10yr' | '15yr' | '20yr' | 'ret5' | 'ret10' | 'ret15';

interface RangeOption {
    value: TimeRange;
    label: string;
    filter: (data: ProjectionYear[], retYear: number | null) => ProjectionYear[];
}

function buildRangeOptions(data: ProjectionYear[], retirementYear: number | null): RangeOption[] {
    if (data.length === 0) return [];

    const startYear = data[0].year;
    const totalYears = data.length;
    const options: RangeOption[] = [
        { value: 'all', label: 'All Years', filter: (d) => d },
    ];

    if (totalYears > 7) {
        options.push({ value: '5yr', label: 'First 5', filter: (d) => d.filter(y => y.year < startYear + 5) });
    }
    if (totalYears > 12) {
        options.push({ value: '10yr', label: 'First 10', filter: (d) => d.filter(y => y.year < startYear + 10) });
    }
    if (totalYears > 17) {
        options.push({ value: '15yr', label: 'First 15', filter: (d) => d.filter(y => y.year < startYear + 15) });
    }
    if (totalYears > 22) {
        options.push({ value: '20yr', label: 'First 20', filter: (d) => d.filter(y => y.year < startYear + 20) });
    }

    if (retirementYear != null) {
        const retiredYears = data.filter(y => y.year >= retirementYear).length;
        if (retiredYears > 7) {
            options.push({
                value: 'ret5',
                label: 'Retire + 5',
                filter: (d) => d.filter(y => y.year >= retirementYear - 1 && y.year < retirementYear + 5),
            });
        }
        if (retiredYears > 12) {
            options.push({
                value: 'ret10',
                label: 'Retire + 10',
                filter: (d) => d.filter(y => y.year >= retirementYear - 1 && y.year < retirementYear + 10),
            });
        }
        if (retiredYears > 17) {
            options.push({
                value: 'ret15',
                label: 'Retire + 15',
                filter: (d) => d.filter(y => y.year >= retirementYear - 1 && y.year < retirementYear + 15),
            });
        }
    }

    // Only show the range selector if there are options beyond "All"
    return options.length > 1 ? options : [];
}

export default function BalanceChart({ data, retirementYear }: BalanceChartProps) {
    const [range, setRange] = useState<TimeRange>('all');

    const rangeOptions = useMemo(() => buildRangeOptions(data, retirementYear), [data, retirementYear]);

    const filteredData = useMemo(() => {
        const opt = rangeOptions.find(o => o.value === range);
        if (!opt) return data;
        return opt.filter(data, retirementYear);
    }, [data, range, rangeOptions, retirementYear]);


    const monthlyData = useMemo(() => interpolateMonthly(filteredData), [filteredData]);
    const depletion = findDepletionYear(filteredData);
    const hasPoolData = filteredData.some(d => d.traditional_balance !== null);
    const hasSpendingProfile = filteredData.some(d => d.essential_expenses != null);
    const crossover = hasSpendingProfile ? findCrossoverYear(filteredData) : null;

    // For single-pool mode, compute cumulative contributions at monthly granularity
    const monthlyChartData = useMemo(() => {
        if (hasPoolData) return monthlyData;
        let cumContrib = 0;
        let yearIdx = 0;
        return monthlyData.map((pt) => {
            while (yearIdx < filteredData.length - 1 && filteredData[yearIdx].year < pt.year) yearIdx++;
            const annualRow = filteredData[yearIdx];
            const monthlyContrib = annualRow ? annualRow.contributions / 12 : 0;
            cumContrib += pt.month === 1 && pt.year === filteredData[0]?.year ? 0 : monthlyContrib;
            return { ...pt, cumulative_contributions: cumContrib };
        });
    }, [monthlyData, filteredData, hasPoolData]);

    const xTickInterval = Math.max(1, Math.floor(monthlyData.length / Math.min(filteredData.length, 30)));

    const xTickFormatter = (label: string) => {
        const month = parseInt(label.substring(5, 7), 10);
        if (month === 1) return label.substring(0, 4);
        return '';
    };

    const retireLabel = retirementYear ? `${retirementYear}-01` : null;
    const depletionLabel = depletion ? `${depletion.year}-01` : null;
    const crossoverLabel = crossover ? `${crossover.year}-01` : null;
    const lastLabel = monthlyData.length > 0 ? monthlyData[monthlyData.length - 1].label : null;

    const monthNames = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

    const BalanceTooltipContent = ({ active, payload, label }: RechartsTooltipProps) => {
        if (!active || !payload?.length) return null;
        const pt = monthlyData.find(d => d.label === label);
        if (!pt) return null;
        const monthName = monthNames[pt.month - 1];
        const annualRow = filteredData.find(d => d.year === pt.year);
        return (
            <div style={tooltipStyle}>
                <div style={{ fontWeight: 600, marginBottom: '0.25rem' }}>
                    {monthName} {pt.year} (age {pt.age})
                </div>
                {payload.map((p) => (
                    <div key={p.name} style={{ color: p.color }}>
                        {p.name}: {formatCurrency(Number(p.value ?? 0))}
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

    // Check if retirement line is within the visible window
    const retireLabelVisible = retireLabel && monthlyData.some(d => d.label === retireLabel);
    const depletionLabelVisible = depletionLabel && monthlyData.some(d => d.label === depletionLabel);
    const crossoverLabelVisible = crossoverLabel && monthlyData.some(d => d.label === crossoverLabel);

    return (
        <div>
            {rangeOptions.length > 0 && (
                <div style={{ display: 'flex', justifyContent: 'flex-end', marginBottom: '0.75rem' }}>
                    <div style={{ display: 'flex', gap: 0, borderRadius: 6, overflow: 'hidden', border: '1px solid #ddd' }}>
                        {rangeOptions.map(opt => (
                            <button
                                key={opt.value}
                                onClick={() => setRange(opt.value)}
                                style={{
                                    padding: '0.3rem 0.65rem',
                                    fontSize: '0.75rem',
                                    border: 'none',
                                    borderRight: '1px solid #ddd',
                                    background: range === opt.value ? '#1976d2' : '#fff',
                                    color: range === opt.value ? '#fff' : '#555',
                                    cursor: 'pointer',
                                    fontWeight: range === opt.value ? 600 : 400,
                                }}
                            >
                                {opt.label}
                            </button>
                        ))}
                    </div>
                </div>
            )}
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
                    <YAxis tickFormatter={formatDollarAxis} tick={{ fontSize: 12 }} width={70} />
                    <Tooltip content={<BalanceTooltipContent />} />
                    <Legend />
                    {retireLabelVisible && <ReferenceLine x={retireLabel!} stroke="#ff9800" strokeDasharray="5 5" label="Retire" />}
                    {depletionLabelVisible && <ReferenceLine x={depletionLabel!} stroke="#d32f2f" strokeDasharray="5 5" label="Depleted" />}
                    {crossoverLabelVisible && (
                        <ReferenceArea
                            x1={crossoverLabel!}
                            x2={depletionLabelVisible ? depletionLabel! : lastLabel ?? crossoverLabel!}
                            fill="#d32f2f"
                            fillOpacity={0.06}
                            strokeOpacity={0}
                        />
                    )}
                    {crossoverLabelVisible && (
                        <ReferenceLine
                            x={crossoverLabel!}
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
        </div>
    );
}
