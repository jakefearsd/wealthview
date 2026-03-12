import {
    BarChart, Bar, XAxis, YAxis, Tooltip, ResponsiveContainer,
    Legend, CartesianGrid, ReferenceLine,
} from 'recharts';
import { formatCurrency } from '../utils/format';
import type { ProjectionYear, ScenarioIncomeSourceResponse } from '../types/projection';

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

    const retirementStartAge = retiredYears[0].age;

    const chartData = retiredYears.map(y => {
        const yearsFromRetirement = y.age - retirementStartAge;
        const row: Record<string, number | string> = { year: y.year, age: y.age };

        for (const source of incomeSources) {
            const active = y.age >= source.start_age && (source.end_age == null || y.age <= source.end_age);
            if (source.one_time) {
                row[source.income_source_id] = y.age === source.start_age ? source.effective_amount : 0;
            } else if (active) {
                row[source.income_source_id] =
                    Math.round(source.effective_amount * Math.pow(1 + source.inflation_rate, yearsFromRetirement) * 100) / 100;
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

    const IncomeTooltipContent = ({ active, payload, label }: any) => {
        if (!active || !payload?.length) return null;
        const d = retiredYears.find(y => y.year === label);
        const visibleItems = payload.filter((p: any) => p.value > 0);
        const total = visibleItems.reduce((sum: number, p: any) => sum + (p.value as number), 0);
        return (
            <div style={{ background: '#fff', border: '1px solid #ccc', padding: '0.75rem', borderRadius: 4, fontSize: '0.85rem' }}>
                <div style={{ fontWeight: 600, marginBottom: '0.25rem' }}>
                    {d ? `${label} (age ${d.age})` : label}
                </div>
                {visibleItems.map((p: any) => (
                    <div key={p.dataKey} style={{ color: p.color }}>
                        {sourceNames[p.dataKey] ?? p.name}: {formatCurrency(p.value)}
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
