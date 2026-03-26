import {
    ComposedChart, Bar, Line, XAxis, YAxis, Tooltip, ResponsiveContainer,
    ReferenceLine, Legend, CartesianGrid,
} from 'recharts';
import { formatCurrency } from '../utils/format';
import { formatDollarAxis, formatPercentAxis } from '../utils/chartFormatters';
import type { ProjectionYear } from '../types/projection';
import ChartTooltip from './ChartTooltip';

interface TaxBreakdownChartProps {
    data: ProjectionYear[];
    retirementYear: number | null;
    hasStateTax: boolean;
}

interface ChartDataPoint {
    year: number;
    age: number;
    federal_tax: number;
    state_tax: number;
    self_employment_tax: number;
    effective_rate: number;
    tax_liability: number;
    salt_deduction: number | null;
    used_itemized_deduction: boolean | null;
    roth_conversion_amount: number | null;
}

export default function TaxBreakdownChart({ data, retirementYear, hasStateTax }: TaxBreakdownChartProps) {
    const retiredYears = data.filter(y => y.retired);

    const hasSETax = retiredYears.some(y => y.self_employment_tax != null && y.self_employment_tax > 0);

    const chartData: ChartDataPoint[] = retiredYears.map(y => {
        const federal = y.federal_tax ?? y.tax_liability ?? 0;
        const state = y.state_tax ?? 0;
        const se = y.self_employment_tax ?? 0;
        const total = y.tax_liability ?? 0;

        const taxableIncome = (y.income_streams_total ?? 0)
            + (y.roth_conversion_amount ?? 0)
            + (y.withdrawal_from_traditional ?? 0);
        const effectiveRate = taxableIncome > 0 ? (total / taxableIncome) * 100 : 0;

        return {
            year: y.year,
            age: y.age,
            federal_tax: hasStateTax ? federal : total - se,
            state_tax: state,
            self_employment_tax: se,
            effective_rate: Math.round(effectiveRate * 10) / 10,
            tax_liability: total,
            salt_deduction: y.salt_deduction,
            used_itemized_deduction: y.used_itemized_deduction,
            roth_conversion_amount: y.roth_conversion_amount,
        };
    });


    return (
        <ResponsiveContainer width="100%" height={450}>
            <ComposedChart data={chartData} margin={{ top: 5, right: 20, bottom: 5, left: 20 }}>
                <CartesianGrid strokeDasharray="3 3" stroke="#f0f0f0" />
                <XAxis dataKey="year" tick={{ fontSize: 12 }} />
                <YAxis yAxisId="dollars" tickFormatter={formatDollarAxis} tick={{ fontSize: 12 }} width={70} />
                <YAxis yAxisId="pct" orientation="right" tickFormatter={formatPercentAxis} tick={{ fontSize: 12 }} width={50} />
                <Tooltip content={
                    <ChartTooltip renderContent={(label) => {
                        const d = chartData.find(y => y.year === label);
                        if (!d) return null;
                        return (
                            <>
                                <div style={{ fontWeight: 600, marginBottom: '0.25rem' }}>
                                    {label} (age {d.age})
                                </div>
                                <div style={{ color: '#d32f2f' }}>
                                    Federal Tax: {formatCurrency(d.federal_tax)}
                                </div>
                                {hasStateTax && d.state_tax > 0 && (
                                    <div style={{ color: '#e65100' }}>
                                        State Tax: {formatCurrency(d.state_tax)}
                                    </div>
                                )}
                                {d.self_employment_tax > 0 && (
                                    <div style={{ color: '#795548' }}>
                                        SE Tax: {formatCurrency(d.self_employment_tax)}
                                    </div>
                                )}
                                <hr style={{ margin: '0.5rem 0', border: 'none', borderTop: '1px solid #e0e0e0' }} />
                                <div style={{ fontWeight: 600, color: '#555' }}>
                                    Total Tax: {formatCurrency(d.tax_liability)}
                                </div>
                                <div style={{ color: '#1976d2' }}>
                                    Effective Rate: {d.effective_rate}%
                                </div>
                                {hasStateTax && d.salt_deduction != null && (
                                    <div style={{ color: '#666' }}>
                                        SALT: {formatCurrency(d.salt_deduction)}
                                    </div>
                                )}
                                {hasStateTax && d.used_itemized_deduction != null && (
                                    <div style={{ color: '#666' }}>
                                        Deduction: {d.used_itemized_deduction ? 'Itemized' : 'Standard'}
                                    </div>
                                )}
                                {d.roth_conversion_amount != null && d.roth_conversion_amount > 0 && (
                                    <div style={{ color: '#666' }}>
                                        Roth Conversion: {formatCurrency(d.roth_conversion_amount)}
                                    </div>
                                )}
                            </>
                        );
                    }} />
                } />
                <Legend />
                {retirementYear && <ReferenceLine yAxisId="dollars" x={retirementYear} stroke="#ff9800" strokeDasharray="5 5" label="Retire" />}
                <Bar yAxisId="dollars" dataKey="federal_tax" stackId="tax" fill="#d32f2f" name="Federal Tax" />
                <Bar yAxisId="dollars" dataKey="state_tax" stackId="tax" fill="#e65100" name="State Tax" hide={!hasStateTax} />
                <Bar yAxisId="dollars" dataKey="self_employment_tax" stackId="tax" fill="#795548" name="SE Tax" hide={!hasSETax} />
                <Line
                    yAxisId="pct"
                    type="monotone"
                    dataKey="effective_rate"
                    stroke="#1976d2"
                    strokeWidth={2}
                    dot={false}
                    name="Effective Rate"
                />
            </ComposedChart>
        </ResponsiveContainer>
    );
}
