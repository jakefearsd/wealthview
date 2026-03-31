import { useMemo } from 'react';
import {
    ComposedChart, Area, Line, XAxis, YAxis, CartesianGrid,
    Tooltip, ResponsiveContainer, Legend,
} from 'recharts';
import type { GuardrailYearlySpending } from '../types/projection';
import { formatDollarAxis, formatDollarTooltip } from '../utils/chartFormatters';
import ChartTooltip from './ChartTooltip';

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
            // Bands for Recharts Area: [low, high]
            outerBand: [y.portfolio_balance_p10, y.portfolio_balance_median],
            innerBand: [y.portfolio_balance_p25, y.portfolio_balance_median],
        }));
    }, [yearlySpending]);


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
                        tickFormatter={formatDollarAxis}
                        label={{ value: 'Portfolio Balance ($)', angle: -90, position: 'insideLeft' }}
                        domain={[0, 'auto']}
                    />
                    <Tooltip content={
                        <ChartTooltip renderContent={(age) => {
                            const d = data.find(y => y.age === age);
                            if (!d) return null;
                            return (
                                <>
                                    <div style={{ fontWeight: 600, marginBottom: '0.25rem' }}>Age {d.age}</div>
                                    <div style={{ color: '#1e1b4b' }}>
                                        Median (p50): {formatDollarTooltip(d.median ?? 0)}
                                    </div>
                                    <div style={{ color: '#6366f1' }}>
                                        25th Percentile: {formatDollarTooltip(d.p25 ?? 0)}
                                    </div>
                                    <div style={{ color: '#ef5350' }}>
                                        10th Percentile: {formatDollarTooltip(d.p10 ?? 0)}
                                    </div>
                                </>
                            );
                        }} />
                    } />
                    <Legend />

                    {/* Outer band: p10 to p50 */}
                    <Area
                        type="monotone"
                        dataKey="outerBand"
                        fill="#c7d2fe"
                        stroke="none"
                        name="10th-50th Percentile"
                        fillOpacity={0.5}
                        legendType="rect"
                    />

                    {/* Inner band: p25 to p50 */}
                    <Area
                        type="monotone"
                        dataKey="innerBand"
                        fill="#818cf8"
                        stroke="none"
                        name="25th-50th Percentile"
                        fillOpacity={0.3}
                        legendType="rect"
                    />

                    {/* p25 line */}
                    <Line
                        type="monotone"
                        dataKey="p25"
                        stroke="#6366f1"
                        strokeWidth={1}
                        strokeDasharray="6 3"
                        dot={false}
                        name="25th Percentile"
                    />

                    {/* Median line */}
                    <Line
                        type="monotone"
                        dataKey="median"
                        stroke="#1e1b4b"
                        strokeWidth={2.5}
                        dot={false}
                        name="Median (p50)"
                    />

                    {/* p10 line (pessimistic boundary) */}
                    <Line
                        type="monotone"
                        dataKey="p10"
                        stroke="#ef5350"
                        strokeWidth={1.5}
                        strokeDasharray="5 5"
                        dot={false}
                        name="10th Percentile"
                    />
                </ComposedChart>
            </ResponsiveContainer>
        </div>
    );
}
