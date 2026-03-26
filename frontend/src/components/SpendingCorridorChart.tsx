import { useMemo } from 'react';
import {
    ComposedChart, Area, Line, XAxis, YAxis, CartesianGrid,
    Tooltip, ResponsiveContainer, Legend, ReferenceLine,
} from 'recharts';
import type { GuardrailYearlySpending, GuardrailPhase } from '../types/projection';
import { formatDollarAxis, formatDollarTooltip } from '../utils/chartFormatters';
import ChartTooltip from './ChartTooltip';

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


    if (data.length === 0) {
        return <p className="text-gray-500">No spending data available.</p>;
    }

    return (
        <div>
            <ResponsiveContainer width="100%" height={400}>
                <ComposedChart data={data} margin={{ top: 20, right: 30, left: 20, bottom: 10 }}>
                    <CartesianGrid strokeDasharray="3 3" stroke="#f0f0f0" />
                    <XAxis dataKey="age" label={{ value: 'Age', position: 'insideBottom', offset: -5 }} />
                    <YAxis tickFormatter={formatDollarAxis} label={{ value: 'Annual Spending ($)', angle: -90, position: 'insideLeft' }} />
                    <Tooltip content={
                        <ChartTooltip renderContent={(age) => {
                            const d = data.find(y => y.age === age);
                            if (!d) return null;
                            return (
                                <>
                                    <div style={{ fontWeight: 600, marginBottom: '0.25rem' }}>Age {d.age}</div>
                                    <div style={{ color: '#2563eb' }}>
                                        Recommended: {formatDollarTooltip(d.recommended)}
                                    </div>
                                    <div style={{ color: '#94a3b8' }}>
                                        Corridor High: {formatDollarTooltip(d.corridorHigh)}
                                    </div>
                                    <div style={{ color: '#94a3b8' }}>
                                        Corridor Low: {formatDollarTooltip(d.corridorLow)}
                                    </div>
                                    <div style={{ color: '#dc2626' }}>
                                        Essential Floor: {formatDollarTooltip(d.essentialFloor)}
                                    </div>
                                    {d.incomeOffset > 0 && (
                                        <div style={{ color: '#22c55e' }}>
                                            Income Offset: {formatDollarTooltip(d.incomeOffset)}
                                        </div>
                                    )}
                                </>
                            );
                        }} />
                    } />
                    <Legend />

                    {/* Corridor band */}
                    <Area
                        type="monotone"
                        dataKey="corridorRange"
                        fill="#e2e8f0"
                        stroke="none"
                        name="Adjustment Range"
                        fillOpacity={0.4}
                        legendType="rect"
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

                    {/* Corridor High dashed line */}
                    <Line
                        type="monotone"
                        dataKey="corridorHigh"
                        stroke="#94a3b8"
                        strokeWidth={1}
                        strokeDasharray="4 4"
                        dot={false}
                        name="Corridor High"
                    />

                    {/* Corridor Low dashed line */}
                    <Line
                        type="monotone"
                        dataKey="corridorLow"
                        stroke="#94a3b8"
                        strokeWidth={1}
                        strokeDasharray="4 4"
                        dot={false}
                        name="Corridor Low"
                    />

                    {/* Recommended spending line */}
                    <Line
                        type="monotone"
                        dataKey="recommended"
                        stroke="#2563eb"
                        strokeWidth={3}
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
                            label={{ value: phase.name, position: 'insideTopLeft', fontSize: 10, fill: '#374151', offset: 15 }}
                        />
                    ))}
                </ComposedChart>
            </ResponsiveContainer>
        </div>
    );
}
