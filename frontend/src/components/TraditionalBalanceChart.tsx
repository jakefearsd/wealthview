import { useMemo } from 'react';
import {
    ComposedChart, Line, XAxis, YAxis, CartesianGrid,
    Tooltip, ResponsiveContainer, Legend, ReferenceLine,
} from 'recharts';
import type { ConversionYearDetail } from '../types/projection';

interface Props {
    years: ConversionYearDetail[];
    exhaustionAge: number;
}

export default function TraditionalBalanceChart({ years, exhaustionAge }: Props) {
    const data = useMemo(() => {
        return years.map(y => ({
            age: y.age,
            traditional: y.traditional_balance_after,
            roth: y.roth_balance_after,
        }));
    }, [years]);

    const fmt = (value: number) =>
        Math.abs(value) >= 1_000_000
            ? `$${(value / 1_000_000).toFixed(1)}M`
            : `$${(value / 1_000).toFixed(0)}k`;

    const tooltipFmt = (value: number) =>
        value.toLocaleString('en-US', { style: 'currency', currency: 'USD', maximumFractionDigits: 0 });

    if (data.length === 0) {
        return <p style={{ color: '#888' }}>No balance trajectory data available.</p>;
    }

    return (
        <div>
            <ResponsiveContainer width="100%" height={400}>
                <ComposedChart data={data} margin={{ top: 10, right: 30, left: 20, bottom: 10 }}>
                    <CartesianGrid strokeDasharray="3 3" stroke="#f0f0f0" />
                    <XAxis
                        dataKey="age"
                        label={{ value: 'Age', position: 'insideBottom', offset: -5 }}
                    />
                    <YAxis
                        tickFormatter={fmt}
                        label={{ value: 'Balance ($)', angle: -90, position: 'insideLeft' }}
                        domain={[0, 'auto']}
                    />
                    <Tooltip
                        formatter={(value: number, name: string) => [tooltipFmt(value), name]}
                        labelFormatter={(age) => `Age ${age}`}
                    />
                    <Legend />

                    <Line
                        type="monotone"
                        dataKey="traditional"
                        stroke="#1976d2"
                        strokeWidth={2}
                        dot={false}
                        name="Traditional IRA"
                    />

                    <Line
                        type="monotone"
                        dataKey="roth"
                        stroke="#2e7d32"
                        strokeWidth={2}
                        dot={false}
                        name="Roth IRA"
                    />

                    <ReferenceLine
                        x={exhaustionAge}
                        stroke="#ff9800"
                        strokeDasharray="5 5"
                        strokeWidth={2}
                        label={{ value: `Exhaustion (${exhaustionAge})`, position: 'top', fontSize: 11, fill: '#ff9800' }}
                    />
                </ComposedChart>
            </ResponsiveContainer>
        </div>
    );
}
