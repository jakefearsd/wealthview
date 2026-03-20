import { useState, useEffect } from 'react';
import { LineChart, Line, XAxis, YAxis, Tooltip, ResponsiveContainer, Legend } from 'recharts';
import { formatCurrency } from '../utils/format';
import { cardStyle } from '../utils/styles';
import HelpText from './HelpText';
import InfoSection from './InfoSection';
import { getDepreciationSchedule } from '../api/properties';
import type { PropertyAnalyticsResponse, DepreciationScheduleResponse } from '../types/property';

interface PropertyAnalyticsSectionProps {
    analytics: PropertyAnalyticsResponse;
    analyticsYear: number | undefined;
    analyticsYearOptions: number[];
    onYearChange: (value: string) => void;
    propertyId: string;
    depreciationMethod: string;
}

export default function PropertyAnalyticsSection({
    analytics,
    analyticsYear,
    analyticsYearOptions,
    onYearChange,
    propertyId,
    depreciationMethod,
}: PropertyAnalyticsSectionProps) {
    const [depreciationSchedule, setDepreciationSchedule] = useState<DepreciationScheduleResponse | null>(null);

    useEffect(() => {
        if (depreciationMethod === 'none') {
            setDepreciationSchedule(null);
            return;
        }
        getDepreciationSchedule(propertyId)
            .then(setDepreciationSchedule)
            .catch(() => setDepreciationSchedule(null));
    }, [propertyId, depreciationMethod]);

    const currentYear = new Date().getFullYear();

    return (
        <>
            <div style={{ ...cardStyle, marginBottom: '2rem' }}>
                <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '1rem' }}>
                    <h3>Property Overview</h3>
                    {analyticsYearOptions.length > 0 && (
                        <select
                            value={analyticsYear ?? ''}
                            onChange={e => onYearChange(e.target.value)}
                            style={{ padding: '0.4rem 0.8rem', border: '1px solid #ccc', borderRadius: '4px', fontSize: '0.85rem' }}
                        >
                            <option value="">Trailing 12 Months</option>
                            {analyticsYearOptions.map(y => (
                                <option key={y} value={y}>{y}</option>
                            ))}
                        </select>
                    )}
                </div>
                <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)', gap: '1rem', marginBottom: '1.5rem' }}>
                    <div>
                        <div style={{ color: '#666', fontSize: '0.85rem' }}>Total Appreciation</div>
                        <div style={{ fontWeight: 600, color: analytics.total_appreciation >= 0 ? '#2e7d32' : '#d32f2f' }}>
                            {formatCurrency(analytics.total_appreciation)}
                        </div>
                    </div>
                    <div>
                        <div style={{ color: '#666', fontSize: '0.85rem' }}>Appreciation %</div>
                        <div style={{ fontWeight: 600, color: analytics.appreciation_percent >= 0 ? '#2e7d32' : '#d32f2f' }}>
                            {analytics.appreciation_percent.toFixed(2)}%
                        </div>
                    </div>
                    {analytics.mortgage_progress && (
                        <div>
                            <div style={{ color: '#666', fontSize: '0.85rem' }}>Months Remaining</div>
                            <div style={{ fontWeight: 600 }}>{analytics.mortgage_progress.months_remaining}</div>
                        </div>
                    )}
                </div>

                {analytics.mortgage_progress && (
                    <div style={{ marginBottom: '1.5rem', padding: '1rem', background: '#f5f5f5', borderRadius: '8px' }}>
                        <h4 style={{ marginBottom: '0.75rem', fontSize: '0.9rem', color: '#444' }}>Mortgage Payoff Progress</h4>
                        <div style={{ background: '#e0e0e0', borderRadius: '4px', height: '24px', marginBottom: '0.75rem', position: 'relative' as const }}>
                            <div style={{
                                background: '#1976d2',
                                height: '100%',
                                borderRadius: '4px',
                                width: `${Math.min(analytics.mortgage_progress.percent_paid_off, 100)}%`,
                                transition: 'width 0.3s',
                                display: 'flex',
                                alignItems: 'center',
                                justifyContent: 'center',
                                color: '#fff',
                                fontSize: '0.75rem',
                                fontWeight: 600,
                            }}>
                                {analytics.mortgage_progress.percent_paid_off.toFixed(1)}%
                            </div>
                        </div>
                        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(4, 1fr)', gap: '1rem', fontSize: '0.85rem' }}>
                            <div><span style={{ color: '#666' }}>Principal Paid:</span> {formatCurrency(analytics.mortgage_progress.principal_paid)}</div>
                            <div><span style={{ color: '#666' }}>Balance:</span> {formatCurrency(analytics.mortgage_progress.current_balance)}</div>
                            <div><span style={{ color: '#666' }}>Payoff Date:</span> {analytics.mortgage_progress.estimated_payoff_date}</div>
                            <div><span style={{ color: '#666' }}>Remaining:</span> {analytics.mortgage_progress.months_remaining} months</div>
                        </div>
                    </div>
                )}

                {analytics.equity_growth.length > 0 && (
                    <div>
                        <h4 style={{ marginBottom: '0.75rem', fontSize: '0.9rem', color: '#444' }}>Equity Growth</h4>
                        <ResponsiveContainer width="100%" height={300}>
                            <LineChart data={analytics.equity_growth}>
                                <XAxis dataKey="month" />
                                <YAxis />
                                <Tooltip formatter={(value: number) => formatCurrency(value)} />
                                <Legend />
                                <Line type="monotone" dataKey="equity" name="Equity" stroke="#2e7d32" strokeWidth={2} dot={false} />
                                <Line type="monotone" dataKey="property_value" name="Property Value" stroke="#1976d2" strokeWidth={1} strokeDasharray="5 5" dot={false} />
                                <Line type="monotone" dataKey="mortgage_balance" name="Mortgage" stroke="#d32f2f" strokeWidth={1} strokeDasharray="5 5" dot={false} />
                            </LineChart>
                        </ResponsiveContainer>
                    </div>
                )}
            </div>

            {analytics.property_type === 'investment' && analytics.cap_rate !== null && (
                <div style={{ ...cardStyle, marginBottom: '2rem' }}>
                    <h3 style={{ marginBottom: '0.5rem' }}>Investment Metrics</h3>
                    <InfoSection prompt="How to read these metrics">
                        Cap Rate and NOI measure the property's operating performance independent of financing. Cash-on-Cash Return measures your actual return based on the cash you invested, factoring in leverage from your mortgage. A higher cap rate or cash-on-cash return indicates a stronger investment.
                    </InfoSection>
                    <div style={{ display: 'grid', gridTemplateColumns: 'repeat(5, 1fr)', gap: '1rem' }}>
                        <div>
                            <div style={{ color: '#666', fontSize: '0.85rem' }}>Cap Rate</div>
                            <div style={{ fontWeight: 600, fontSize: '1.2rem' }}>{analytics.cap_rate!.toFixed(2)}%</div>
                            <HelpText>Annual NOI / property value. Measures return independent of financing.</HelpText>
                        </div>
                        <div>
                            <div style={{ color: '#666', fontSize: '0.85rem' }}>Cash-on-Cash Return</div>
                            <div style={{ fontWeight: 600, fontSize: '1.2rem', color: analytics.cash_on_cash_return! >= 0 ? '#2e7d32' : '#d32f2f' }}>
                                {analytics.cash_on_cash_return!.toFixed(2)}%
                            </div>
                            <HelpText>Annual net cash flow / cash invested. Your actual return accounting for leverage.</HelpText>
                        </div>
                        <div>
                            <div style={{ color: '#666', fontSize: '0.85rem' }}>Annual NOI</div>
                            <div style={{ fontWeight: 600 }}>{formatCurrency(analytics.annual_noi!)}</div>
                            <HelpText>Net Operating Income: rental income minus operating expenses, excluding mortgage.</HelpText>
                        </div>
                        <div>
                            <div style={{ color: '#666', fontSize: '0.85rem' }}>Net Cash Flow</div>
                            <div style={{ fontWeight: 600, color: analytics.annual_net_cash_flow! >= 0 ? '#2e7d32' : '#d32f2f' }}>
                                {formatCurrency(analytics.annual_net_cash_flow!)}
                            </div>
                            <HelpText>Cash remaining after all expenses including mortgage payments.</HelpText>
                        </div>
                        <div>
                            <div style={{ color: '#666', fontSize: '0.85rem' }}>Cash Invested</div>
                            <div style={{ fontWeight: 600 }}>{formatCurrency(analytics.total_cash_invested!)}</div>
                            <HelpText>Your out-of-pocket investment: purchase price minus loan amount.</HelpText>
                        </div>
                    </div>
                </div>
            )}

            {depreciationSchedule && depreciationSchedule.schedule.length > 0 && (
                <div style={{ ...cardStyle, marginBottom: '2rem' }}>
                    <h3 style={{ marginBottom: '0.5rem' }}>Depreciation Schedule</h3>
                    <div style={{ display: 'flex', gap: '2rem', fontSize: '0.85rem', color: '#666', marginBottom: '1rem', flexWrap: 'wrap' }}>
                        <span>{depreciationSchedule.depreciation_method === 'cost_segregation' ? 'Cost Segregation' : 'Straight-Line Depreciation'}</span>
                        <span>Depreciable Basis: {formatCurrency(depreciationSchedule.depreciable_basis)}</span>
                        {depreciationSchedule.bonus_depreciation_rate != null && (
                            <span>Bonus Rate: {(depreciationSchedule.bonus_depreciation_rate * 100).toFixed(0)}%</span>
                        )}
                        {depreciationSchedule.depreciation_method !== 'cost_segregation' && (
                            <span>Annual: {formatCurrency(depreciationSchedule.schedule[0]?.annual_depreciation ?? 0)}</span>
                        )}
                    </div>

                    {depreciationSchedule.class_breakdowns && depreciationSchedule.class_breakdowns.length > 0 && (
                        <div style={{ marginBottom: '1.5rem' }}>
                            <h4 style={{ marginBottom: '0.5rem', fontSize: '0.9rem', color: '#444' }}>Asset Class Breakdown</h4>
                            <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: '0.85rem', marginBottom: '1rem' }}>
                                <thead>
                                    <tr style={{ borderBottom: '2px solid #eee', textAlign: 'left' }}>
                                        <th style={{ padding: '0.4rem 0.5rem' }}>Class</th>
                                        <th style={{ padding: '0.4rem 0.5rem', textAlign: 'right' }}>Allocation</th>
                                        <th style={{ padding: '0.4rem 0.5rem', textAlign: 'right' }}>Bonus</th>
                                        <th style={{ padding: '0.4rem 0.5rem', textAlign: 'right' }}>Annual SL</th>
                                        <th style={{ padding: '0.4rem 0.5rem', textAlign: 'right' }}>SL Years</th>
                                    </tr>
                                </thead>
                                <tbody>
                                    {depreciationSchedule.class_breakdowns.map((cb) => (
                                        <tr key={cb.asset_class} style={{ borderBottom: '1px solid #f0f0f0' }}>
                                            <td style={{ padding: '0.4rem 0.5rem', fontWeight: 600 }}>
                                                {cb.asset_class === '27_5yr' ? '27.5-Year' : cb.asset_class.replace('yr', '-Year')}
                                            </td>
                                            <td style={{ padding: '0.4rem 0.5rem', textAlign: 'right' }}>{formatCurrency(cb.allocation)}</td>
                                            <td style={{ padding: '0.4rem 0.5rem', textAlign: 'right' }}>{formatCurrency(cb.bonus_amount)}</td>
                                            <td style={{ padding: '0.4rem 0.5rem', textAlign: 'right' }}>{cb.annual_straight_line > 0 ? formatCurrency(cb.annual_straight_line) : '—'}</td>
                                            <td style={{ padding: '0.4rem 0.5rem', textAlign: 'right' }}>{cb.straight_line_years > 0 ? cb.straight_line_years : '—'}</td>
                                        </tr>
                                    ))}
                                </tbody>
                            </table>
                        </div>
                    )}

                    <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: '0.9rem' }}>
                        <thead>
                            <tr style={{ borderBottom: '2px solid #eee', textAlign: 'left' }}>
                                <th style={{ padding: '0.5rem' }}>Tax Year</th>
                                <th style={{ padding: '0.5rem', textAlign: 'right' }}>Annual Depreciation</th>
                                <th style={{ padding: '0.5rem', textAlign: 'right' }}>Cumulative Taken</th>
                                <th style={{ padding: '0.5rem', textAlign: 'right' }}>Remaining Basis</th>
                            </tr>
                        </thead>
                        <tbody>
                            {depreciationSchedule.schedule.map((row) => (
                                <tr
                                    key={row.tax_year}
                                    style={{
                                        borderBottom: '1px solid #eee',
                                        background: row.tax_year === currentYear ? '#fff8e1' : undefined,
                                    }}
                                >
                                    <td style={{ padding: '0.5rem', fontWeight: row.tax_year === currentYear ? 600 : 400 }}>
                                        {row.tax_year}
                                    </td>
                                    <td style={{ padding: '0.5rem', textAlign: 'right' }}>
                                        {formatCurrency(row.annual_depreciation)}
                                    </td>
                                    <td style={{ padding: '0.5rem', textAlign: 'right' }}>
                                        {formatCurrency(row.cumulative_taken)}
                                    </td>
                                    <td style={{ padding: '0.5rem', textAlign: 'right' }}>
                                        {formatCurrency(row.remaining_basis)}
                                    </td>
                                </tr>
                            ))}
                        </tbody>
                    </table>
                </div>
            )}
        </>
    );
}
