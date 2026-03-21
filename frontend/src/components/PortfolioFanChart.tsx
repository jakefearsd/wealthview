import { useMemo } from 'react';
import {
    ComposedChart, Area, Line, XAxis, YAxis, CartesianGrid,
    Tooltip, ResponsiveContainer, Legend,
} from 'recharts';
import type { GuardrailYearlySpending } from '../types/projection';

interface Props {
    yearlySpending: GuardrailYearlySpending[];
}

export default function PortfolioFanChart({ yearlySpending }: Props) {
    const data = useMemo(() => {
        return yearlySpending.map(y => ({
            age: y.age,
            p10: y.portfolio_balance_p10,
            p25: y.portfolio_balance_p25,
            median: y.portfolio_balance_median,
            p75: y.portfolio_balance_p75,
            // Bands for Recharts Area: [low, high]
            outerBand: [y.portfolio_balance_p10, y.portfolio_balance_p75],
            innerBand: [y.portfolio_balance_p25, y.portfolio_balance_median],
        }));
    }, [yearlySpending]);

    const fmt = (value: number) =>
        Math.abs(value) >= 1000000
            ? `$${(value / 1000000).toFixed(1)}M`
            : `$${(value / 1000).toFixed(0)}k`;

    const tooltipFmt = (value: number) =>
        value.toLocaleString('en-US', { style: 'currency', currency: 'USD', maximumFractionDigits: 0 });

    if (data.length === 0) {
        return <p style={{ color: '#888' }}>No portfolio balance data available.</p>;
    }

    return (
        <div data-testid="portfolio-fan-chart">
            <ResponsiveContainer width="100%" height={400}>
                <ComposedChart data={data} margin={{ top: 10, right: 30, left: 20, bottom: 10 }}>
                    <CartesianGrid strokeDasharray="3 3" stroke="#f0f0f0" />
                    <XAxis
                        dataKey="age"
                        label={{ value: 'Age', position: 'insideBottom', offset: -5 }}
                    />
                    <YAxis
                        tickFormatter={fmt}
                        label={{ value: 'Portfolio Balance ($)', angle: -90, position: 'insideLeft' }}
                        domain={[0, 'auto']}
                    />
                    <Tooltip
                        formatter={(value: number, name: string) => [tooltipFmt(value), name]}
                        labelFormatter={(age) => `Age ${age}`}
                    />
                    <Legend />

                    {/* Outer band: p10 to p75 */}
                    <Area
                        type="monotone"
                        dataKey="outerBand"
                        fill="#e3f2fd"
                        stroke="none"
                        name="10th-75th Percentile"
                        fillOpacity={0.5}
                    />

                    {/* Inner band: p25 to p50 */}
                    <Area
                        type="monotone"
                        dataKey="innerBand"
                        fill="#90caf9"
                        stroke="none"
                        name="25th-50th Percentile"
                        fillOpacity={0.5}
                    />

                    {/* Median line */}
                    <Line
                        type="monotone"
                        dataKey="median"
                        stroke="#1565c0"
                        strokeWidth={2}
                        dot={false}
                        name="Median (p50)"
                    />

                    {/* p10 line (pessimistic boundary) */}
                    <Line
                        type="monotone"
                        dataKey="p10"
                        stroke="#ef5350"
                        strokeWidth={1}
                        strokeDasharray="5 5"
                        dot={false}
                        name="10th Percentile"
                    />
                </ComposedChart>
            </ResponsiveContainer>
        </div>
    );
}
