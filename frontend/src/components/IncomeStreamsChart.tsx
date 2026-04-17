import {
    BarChart, Bar, XAxis, YAxis, Tooltip, ResponsiveContainer,
    Legend, CartesianGrid, ReferenceLine,
} from 'recharts';
import { formatCurrency } from '../utils/format';
import { tooltipStyle } from '../utils/styles';
import type { ProjectionYear, ScenarioIncomeSourceResponse } from '../types/projection';
import type { RechartsTooltipProps } from '../types/recharts';

const COLORS = [
    '#1976d2', '#2e7d32', '#e65100', '#6a1b9a', '#c62828',
    '#00838f', '#4e342e', '#283593', '#558b2f', '#ad1457',
];

interface IncomeStreamsChartProps {
    data: ProjectionYear[];
    incomeSources: ScenarioIncomeSourceResponse[];
    retirementYear: number | null;
}

export default function IncomeStreamsChart({ data, incomeSources, retirementYear }: IncomeStreamsChartProps) {
    const tickFormatter = (v: number) =>
        Math.abs(v) >= 1000000 ? `$${(v / 1000000).toFixed(1)}M` : `$${(v / 1000).toFixed(0)}k`;

    const retiredYears = data.filter(y => y.retired);
    if (retiredYears.length === 0 || incomeSources.length === 0) return null;

    const chartData = retiredYears.map(y => {
        const row: Record<string, number | string> = { year: y.year, age: y.age };

        for (const source of incomeSources) {
            if (y.income_by_source && source.income_source_id in y.income_by_source) {
                row[source.income_source_id] = y.income_by_source[source.income_source_id];
            } else {
                row[source.income_source_id] = 0;
            }
        }

        return row;
    });

    const sourceNames: Record<string, string> = {};
    for (const s of incomeSources) {
        sourceNames[s.income_source_id] = s.name;
    }

    const IncomeTooltipContent = ({ active, payload, label }: RechartsTooltipProps) => {
        if (!active || !payload?.length) return null;
        const d = retiredYears.find(y => y.year === label);
        const visibleItems = payload.filter((p) => (p.value ?? 0) > 0);
        const total = visibleItems.reduce((sum, p) => sum + (p.value ?? 0), 0);
        return (
            <div style={tooltipStyle}>
                <div style={{ fontWeight: 600, marginBottom: '0.25rem' }}>
                    {d ? `${label} (age ${d.age})` : label}
                </div>
                {visibleItems.map((p) => (
                    <div key={String(p.dataKey)} style={{ color: p.color }}>
                        {sourceNames[String(p.dataKey)] ?? p.name}: {formatCurrency(Number(p.value ?? 0))}
                    </div>
                ))}
                {visibleItems.length > 1 && (
                    <>
                        <hr style={{ margin: '0.5rem 0', border: 'none', borderTop: '1px solid #e0e0e0' }} />
                        <div style={{ fontWeight: 600, color: '#555' }}>
                            Total: {formatCurrency(total)}
                        </div>
                    </>
                )}
            </div>
        );
    };

    return (
        <ResponsiveContainer width="100%" height={450}>
            <BarChart data={chartData} margin={{ top: 5, right: 20, bottom: 5, left: 20 }}>
                <CartesianGrid strokeDasharray="3 3" stroke="#f0f0f0" />
                <XAxis dataKey="year" tick={{ fontSize: 12 }} />
                <YAxis tickFormatter={tickFormatter} tick={{ fontSize: 12 }} width={70} />
                <Tooltip content={<IncomeTooltipContent />} />
                <Legend />
                {retirementYear && <ReferenceLine x={retirementYear} stroke="#ff9800" strokeDasharray="5 5" label="Retire" />}
                {incomeSources.map((source, i) => (
                    <Bar
                        key={source.income_source_id}
                        dataKey={source.income_source_id}
                        stackId="income"
                        fill={COLORS[i % COLORS.length]}
                        name={source.name}
                    />
                ))}
            </BarChart>
        </ResponsiveContainer>
    );
}
