import { useMemo } from 'react';
import {
    ComposedChart, Area, Line, XAxis, YAxis, CartesianGrid,
    Tooltip, ResponsiveContainer, Legend, ReferenceLine,
} from 'recharts';
import type { GuardrailYearlySpending, GuardrailPhase } from '../types/projection';

interface Props {
    yearlySpending: GuardrailYearlySpending[];
    phases: GuardrailPhase[];
}

const PHASE_COLORS = ['#93c5fd', '#86efac', '#fcd34d', '#fca5a5', '#c4b5fd'];

export default function SpendingCorridorChart({ yearlySpending, phases }: Props) {
    const data = useMemo(() => {
        return yearlySpending.map(y => ({
            age: y.age,
            recommended: y.recommended,
            corridorLow: y.corridor_low,
            corridorHigh: y.corridor_high,
            corridorRange: [y.corridor_low, y.corridor_high],
            essentialFloor: y.essential_floor,
            incomeOffset: y.income_offset,
            portfolioWithdrawal: y.portfolio_withdrawal,
            phaseName: y.phase_name,
        }));
    }, [yearlySpending]);

    const fmt = (value: number) =>
        `$${(value / 1000).toFixed(0)}k`;

    const tooltipFmt = (value: number) =>
        value.toLocaleString('en-US', { style: 'currency', currency: 'USD', maximumFractionDigits: 0 });

    if (data.length === 0) {
        return <p className="text-gray-500">No spending data available.</p>;
    }

    return (
        <div>
            <ResponsiveContainer width="100%" height={400}>
                <ComposedChart data={data} margin={{ top: 10, right: 30, left: 20, bottom: 10 }}>
                    <CartesianGrid strokeDasharray="3 3" stroke="#f0f0f0" />
                    <XAxis dataKey="age" label={{ value: 'Age', position: 'insideBottom', offset: -5 }} />
                    <YAxis tickFormatter={fmt} label={{ value: 'Annual Spending ($)', angle: -90, position: 'insideLeft' }} />
                    <Tooltip
                        formatter={(value: number, name: string) => [tooltipFmt(value), name]}
                        labelFormatter={(age) => `Age ${age}`}
                    />
                    <Legend />

                    {/* Corridor band */}
                    <Area
                        type="monotone"
                        dataKey="corridorRange"
                        fill="#dbeafe"
                        stroke="none"
                        name="Confidence Corridor (10th-90th)"
                        fillOpacity={0.6}
                    />

                    {/* Income offset area */}
                    <Area
                        type="monotone"
                        dataKey="incomeOffset"
                        fill="#bbf7d0"
                        stroke="#22c55e"
                        strokeWidth={1}
                        fillOpacity={0.4}
                        name="Income Offset"
                    />

                    {/* Recommended spending line */}
                    <Line
                        type="monotone"
                        dataKey="recommended"
                        stroke="#2563eb"
                        strokeWidth={2}
                        dot={false}
                        name="Recommended Spending"
                    />

                    {/* Essential floor dashed line */}
                    <Line
                        type="monotone"
                        dataKey="essentialFloor"
                        stroke="#dc2626"
                        strokeWidth={1}
                        strokeDasharray="5 5"
                        dot={false}
                        name="Essential Floor"
                    />

                    {/* Phase boundaries */}
                    {phases.map((phase, i) => (
                        <ReferenceLine
                            key={`phase-start-${i}`}
                            x={phase.start_age}
                            stroke={PHASE_COLORS[i % PHASE_COLORS.length]}
                            strokeDasharray="3 3"
                            label={{ value: phase.name, position: 'top', fontSize: 10 }}
                        />
                    ))}
                </ComposedChart>
            </ResponsiveContainer>
        </div>
    );
}
