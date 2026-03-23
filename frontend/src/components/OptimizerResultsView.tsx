import type { GuardrailProfileResponse } from '../types/projection';
import { computePlanDiagnostics } from '../pages/SpendingOptimizerPage';
import { cardStyle } from '../utils/styles';
import SpendingCorridorChart from './SpendingCorridorChart';
import PortfolioFanChart from './PortfolioFanChart';
import TaxSavingsSummary from './TaxSavingsSummary';
import ConversionScheduleTable from './ConversionScheduleTable';
import TraditionalBalanceChart from './TraditionalBalanceChart';

interface OptimizerResultsViewProps {
    result: GuardrailProfileResponse;
    onReoptimize: () => void;
    fmt: (n: number | null | undefined) => string;
    fmtShort: (n: number | null | undefined) => string;
    pct: (n: number | null | undefined) => string;
}

export default function OptimizerResultsView({
    result,
    onReoptimize,
    fmt,
    fmtShort,
    pct,
}: OptimizerResultsViewProps) {
    const diagnostics = computePlanDiagnostics(result.phases, result.yearly_spending, result.failure_rate);
    const failureRateColors = { good: '#e8f5e9', caution: '#fff8e1', danger: '#ffebee' };
    const failureRateBorder = { good: '#a5d6a7', caution: '#ffe082', danger: '#ef9a9a' };

    return (
        <div>
            {result.stale && (
                <div style={{
                    background: '#fff8e1', border: '1px solid #ffe082', borderRadius: '8px',
                    padding: '1rem', display: 'flex', justifyContent: 'space-between',
                    alignItems: 'center', marginBottom: '1.5rem',
                }}>
                    <span style={{ color: '#e65100' }}>This profile is stale &mdash; the scenario has changed since optimization.</span>
                    <button onClick={onReoptimize}
                        style={{ padding: '0.35rem 0.75rem', background: '#ff9800', color: '#fff', border: 'none', borderRadius: '4px', cursor: 'pointer', fontWeight: 600 }}>
                        Re-optimize
                    </button>
                </div>
            )}

            {diagnostics.warnings.length > 0 && (
                <div data-testid="warning-banner" style={{
                    background: diagnostics.failureRateSeverity === 'danger' ? '#ffebee' : '#fff8e1',
                    border: `1px solid ${diagnostics.failureRateSeverity === 'danger' ? '#ef9a9a' : '#ffe082'}`,
                    borderRadius: '8px', padding: '1rem', marginBottom: '1.5rem',
                }}>
                    <div style={{ fontWeight: 600, marginBottom: '0.5rem', color: diagnostics.failureRateSeverity === 'danger' ? '#c62828' : '#e65100' }}>
                        Plan Warnings
                    </div>
                    <ul style={{ margin: 0, paddingLeft: '1.25rem' }}>
                        {diagnostics.warnings.map((w, i) => (
                            <li key={i} style={{ fontSize: '0.9rem', color: '#333' }}>{w}</li>
                        ))}
                    </ul>
                </div>
            )}

            <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr 1fr 1fr 1fr', gap: '1rem', marginBottom: '1.5rem' }}>
                <div data-testid="failure-rate-card" style={{
                    ...cardStyle, textAlign: 'center',
                    background: failureRateColors[diagnostics.failureRateSeverity],
                    border: `1px solid ${failureRateBorder[diagnostics.failureRateSeverity]}`,
                }}>
                    <div style={{ fontSize: '0.8rem', color: '#888', marginBottom: '0.25rem' }}>Failure Rate</div>
                    <div style={{ fontSize: '1.25rem', fontWeight: 700 }}>{pct(result.failure_rate)}</div>
                </div>
                <div style={{ ...cardStyle, textAlign: 'center' }}>
                    <div style={{ fontSize: '0.8rem', color: '#888', marginBottom: '0.25rem' }}>10th Percentile Final</div>
                    <div style={{ fontSize: '1.25rem', fontWeight: 700 }}>{fmt(result.percentile10_final)}</div>
                </div>
                <div style={{ ...cardStyle, textAlign: 'center' }}>
                    <div style={{ fontSize: '0.8rem', color: '#888', marginBottom: '0.25rem' }}>25th Percentile Final</div>
                    <div style={{ fontSize: '1.25rem', fontWeight: 700 }}>{fmt(result.yearly_spending[result.yearly_spending.length - 1]?.portfolio_balance_p25 ?? 0)}</div>
                </div>
                <div style={{ ...cardStyle, textAlign: 'center' }}>
                    <div style={{ fontSize: '0.8rem', color: '#888', marginBottom: '0.25rem' }}>Median Final Balance</div>
                    <div style={{ fontSize: '1.25rem', fontWeight: 700 }}>{fmt(result.median_final_balance)}</div>
                </div>
                <div data-testid="p55-card" style={{ ...cardStyle, textAlign: 'center' }}>
                    <div style={{ fontSize: '0.8rem', color: '#888', marginBottom: '0.25rem' }}>55th Percentile Final</div>
                    <div style={{ fontSize: '1.25rem', fontWeight: 700 }}>{fmt(result.percentile55_final)}</div>
                </div>
            </div>

            {(() => {
                const lastYear = result.yearly_spending[result.yearly_spending.length - 1];
                return lastYear && (lastYear.portfolio_balance_p10 != null || lastYear.portfolio_balance_median != null) ? (
                    <div data-testid="outcome-range-card" style={{ ...cardStyle, marginBottom: '1.5rem' }}>
                        <div style={{ fontSize: '0.8rem', color: '#888', marginBottom: '0.5rem' }}>Outcome Range (Final Portfolio Balance)</div>
                        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                            <div>
                                <div style={{ fontSize: '0.75rem', color: '#ef5350' }}>Pessimistic (p10)</div>
                                <div style={{ fontSize: '1.1rem', fontWeight: 600 }}>{fmt(lastYear.portfolio_balance_p10)}</div>
                            </div>
                            <div style={{ flex: 1, margin: '0 0.75rem' }}>
                                <div style={{ height: '4px', background: 'linear-gradient(to right, #ef5350, #ff9800, #4caf50, #1976d2)', borderRadius: '2px' }} />
                                <div style={{ display: 'flex', justifyContent: 'center', gap: '1rem', marginTop: '0.25rem' }}>
                                    <span style={{ fontSize: '0.7rem', color: '#888' }}>p25: {fmt(lastYear.portfolio_balance_p25)}</span>
                                    <span style={{ fontSize: '0.7rem', color: '#888' }}>p50: {fmt(lastYear.portfolio_balance_median)}</span>
                                </div>
                            </div>
                            <div style={{ textAlign: 'right' }}>
                                <div style={{ fontSize: '0.75rem', color: '#1976d2' }}>Slightly above median (p55)</div>
                                <div style={{ fontSize: '1.1rem', fontWeight: 600 }}>{fmt(result.percentile55_final)}</div>
                            </div>
                        </div>
                        <div style={{ textAlign: 'center', fontSize: '0.75rem', color: '#888', marginTop: '0.25rem' }}>
                            Final year portfolio balance at age {lastYear.age}
                        </div>
                    </div>
                ) : null;
            })()}

            <div data-testid="tax-disclaimer" style={{
                padding: '0.75rem 1rem', marginBottom: '1.5rem',
                background: '#fff3e0', border: '1px solid #ffe0b2', borderRadius: '6px',
                fontSize: '0.8rem', color: '#e65100',
            }}>
                <strong>Note:</strong> Spending recommendations account for income tax on
                traditional account withdrawals using your scenario&apos;s filing status and
                withdrawal ordering. Actual tax liability may vary based on deductions,
                credits, and state taxes not fully modeled in the Monte Carlo simulation.
            </div>

            {diagnostics.phases.length > 0 && (
                <div style={{ ...cardStyle, marginBottom: '1.5rem' }}>
                    <h3 style={{ marginBottom: '1rem' }}>Phase Achievement</h3>
                    <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: '0.85rem' }}>
                        <thead>
                            <tr style={{ borderBottom: '2px solid #e0e0e0' }}>
                                <th style={{ textAlign: 'left', padding: '0.5rem' }}>Phase</th>
                                <th style={{ textAlign: 'left', padding: '0.5rem' }}>Ages</th>
                                <th style={{ textAlign: 'right', padding: '0.5rem' }}>Target</th>
                                <th style={{ textAlign: 'right', padding: '0.5rem' }}>Avg Recommended</th>
                                <th style={{ padding: '0.5rem', width: '35%' }}>Achievement</th>
                            </tr>
                        </thead>
                        <tbody>
                            {diagnostics.phases.map(p => {
                                const barColor = p.achievementPct >= 90 ? '#4caf50'
                                    : p.achievementPct >= 70 ? '#ff9800' : '#ef5350';
                                const phase = result.phases.find(ph => ph.name === p.phaseName);
                                const ageRange = phase
                                    ? `${phase.start_age}\u2013${phase.end_age ?? '\u221E'}`
                                    : '';
                                return (
                                    <tr key={p.phaseName} style={{ borderBottom: '1px solid #eee' }}>
                                        <td style={{ padding: '0.5rem' }}>{p.phaseName}</td>
                                        <td style={{ padding: '0.5rem', color: '#888' }}>{ageRange}</td>
                                        <td style={{ padding: '0.5rem', textAlign: 'right' }}>{fmt(p.targetSpending)}</td>
                                        <td style={{ padding: '0.5rem', textAlign: 'right' }}>{fmt(p.avgRecommended)}</td>
                                        <td style={{ padding: '0.5rem' }}>
                                            <div style={{ display: 'flex', alignItems: 'center', gap: '0.5rem' }}>
                                                <div data-testid={`progress-bar-${p.phaseName}`} style={{
                                                    flex: 1, height: '0.75rem', background: '#eee',
                                                    borderRadius: '4px', overflow: 'hidden',
                                                }}>
                                                    <div style={{
                                                        width: `${Math.min(100, p.achievementPct)}%`,
                                                        height: '100%', background: barColor,
                                                        borderRadius: '4px',
                                                    }} />
                                                </div>
                                                <span style={{ fontSize: '0.8rem', fontWeight: 600, minWidth: '3rem', textAlign: 'right' }}>
                                                    {Math.round(p.achievementPct)}%
                                                </span>
                                            </div>
                                        </td>
                                    </tr>
                                );
                            })}
                        </tbody>
                    </table>
                </div>
            )}

            <div style={{ ...cardStyle, marginBottom: '1.5rem' }}>
                <h3 style={{ marginBottom: '1rem' }}>Portfolio Balance Projections</h3>
                <p style={{ fontSize: '0.85rem', color: '#555', marginBottom: '0.75rem', lineHeight: 1.5 }}>
                    Portfolio balance projections across thousands of market simulations. The dark line shows the
                    median outcome. The shaded bands show the range between pessimistic (10th percentile) and
                    slightly-above-average (55th percentile) scenarios. The red dashed line is the worst-case floor.
                </p>
                <PortfolioFanChart yearlySpending={result.yearly_spending} />
            </div>

            <div style={{ ...cardStyle, marginBottom: '1.5rem' }}>
                <h3 style={{ marginBottom: '1rem' }}>Spending Corridor</h3>
                <p style={{ fontSize: '0.85rem', color: '#555', marginBottom: '0.75rem', lineHeight: 1.5 }}>
                    The blue line shows recommended spending at your confidence level. The shaded band shows the
                    adjustment range — spend near the top in good markets, cut toward the bottom in downturns.
                    The green area represents income that offsets portfolio withdrawals.
                </p>
                <SpendingCorridorChart yearlySpending={result.yearly_spending} phases={result.phases} />
            </div>

            <div style={{ ...cardStyle, marginBottom: '1.5rem', overflowX: 'auto' }}>
                <h3 style={{ marginBottom: '1rem' }}>Year-by-Year Breakdown</h3>
                <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: '0.85rem' }}>
                    <thead>
                        <tr style={{ borderBottom: '1px solid #e0e0e0' }}>
                            <th rowSpan={2} style={{ textAlign: 'left', padding: '0.5rem', verticalAlign: 'bottom' }}>Age</th>
                            <th rowSpan={2} style={{ textAlign: 'left', padding: '0.5rem', verticalAlign: 'bottom' }}>Phase</th>
                            <th rowSpan={2} style={{ textAlign: 'right', padding: '0.5rem', verticalAlign: 'bottom' }}>Recommended</th>
                            <th rowSpan={2} style={{ textAlign: 'right', padding: '0.5rem', verticalAlign: 'bottom' }}>Floor</th>
                            <th rowSpan={2} style={{ textAlign: 'right', padding: '0.5rem', verticalAlign: 'bottom' }}>Discretionary</th>
                            <th rowSpan={2} style={{ textAlign: 'right', padding: '0.5rem', verticalAlign: 'bottom' }}>Income</th>
                            <th rowSpan={2} style={{ textAlign: 'right', padding: '0.5rem', verticalAlign: 'bottom' }}>Portfolio Draw</th>
                            <th colSpan={4} style={{ textAlign: 'center', padding: '0.5rem', borderBottom: '1px solid #e0e0e0' }}>Portfolio Balance</th>
                            <th rowSpan={2} style={{ textAlign: 'right', padding: '0.5rem', verticalAlign: 'bottom' }}>Corridor</th>
                        </tr>
                        <tr style={{ borderBottom: '2px solid #e0e0e0' }}>
                            <th style={{ textAlign: 'right', padding: '0.25rem 0.5rem', fontSize: '0.75rem', color: '#888' }}>p10</th>
                            <th style={{ textAlign: 'right', padding: '0.25rem 0.5rem', fontSize: '0.75rem', color: '#888' }}>p25</th>
                            <th style={{ textAlign: 'right', padding: '0.25rem 0.5rem', fontSize: '0.75rem', color: '#888' }}>p50</th>
                            <th style={{ textAlign: 'right', padding: '0.25rem 0.5rem', fontSize: '0.75rem', color: '#888' }}>p55</th>
                        </tr>
                    </thead>
                    <tbody>
                        {result.yearly_spending.map(y => (
                            <tr key={y.year} style={{ borderBottom: '1px solid #eee' }}>
                                <td style={{ padding: '0.4rem 0.5rem' }}>{y.age}</td>
                                <td style={{ padding: '0.4rem 0.5rem', color: '#666' }}>{y.phase_name}</td>
                                <td style={{ padding: '0.4rem 0.5rem', textAlign: 'right', fontWeight: 600 }}>{fmt(y.recommended)}</td>
                                <td style={{ padding: '0.4rem 0.5rem', textAlign: 'right' }}>{fmt(y.essential_floor)}</td>
                                <td style={{ padding: '0.4rem 0.5rem', textAlign: 'right' }}>{fmt(y.discretionary)}</td>
                                <td style={{ padding: '0.4rem 0.5rem', textAlign: 'right' }}>{fmt(y.income_offset)}</td>
                                <td style={{ padding: '0.4rem 0.5rem', textAlign: 'right' }}>{fmt(y.portfolio_withdrawal)}</td>
                                <td style={{
                                    padding: '0.4rem 0.5rem', textAlign: 'right',
                                    color: y.portfolio_balance_p10 != null && y.portfolio_balance_p10 <= 0 ? '#ef5350' : '#888',
                                }}>
                                    {fmtShort(y.portfolio_balance_p10)}
                                </td>
                                <td style={{ padding: '0.4rem 0.5rem', textAlign: 'right', color: '#888' }}>
                                    {fmtShort(y.portfolio_balance_p25)}
                                </td>
                                <td style={{ padding: '0.4rem 0.5rem', textAlign: 'right', color: '#888' }}>
                                    {fmtShort(y.portfolio_balance_median)}
                                </td>
                                <td style={{ padding: '0.4rem 0.5rem', textAlign: 'right', color: '#888' }}>
                                    {fmtShort(y.portfolio_balance_p55)}
                                </td>
                                <td style={{ padding: '0.4rem 0.5rem', textAlign: 'right', color: '#888' }}>
                                    {fmt(y.corridor_low)} &ndash; {fmt(y.corridor_high)}
                                </td>
                            </tr>
                        ))}
                    </tbody>
                </table>
            </div>

            {result.conversion_schedule && (
                <div style={{ marginTop: '2rem' }}>
                    <h3 style={{ marginBottom: '1rem' }}>Roth Conversion Strategy</h3>
                    <TaxSavingsSummary schedule={result.conversion_schedule} />

                    <div style={{ ...cardStyle, marginBottom: '1.5rem' }}>
                        <h4 style={{ marginBottom: '1rem', marginTop: 0 }}>Traditional &amp; Roth IRA Balance Trajectory</h4>
                        <TraditionalBalanceChart
                            years={result.conversion_schedule.years}
                            exhaustionAge={result.conversion_schedule.exhaustion_age}
                        />
                    </div>

                    <div style={{ ...cardStyle, marginBottom: '1.5rem', overflowX: 'auto' }}>
                        <h4 style={{ marginBottom: '1rem', marginTop: 0 }}>Conversion Schedule</h4>
                        <ConversionScheduleTable years={result.conversion_schedule.years} />
                    </div>
                </div>
            )}

            <div style={{ display: 'flex', gap: '0.75rem' }}>
            </div>
        </div>
    );
}
