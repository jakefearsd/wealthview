import type { GuardrailProfileResponse } from '../types/projection';
import { computePlanDiagnostics } from '../pages/SpendingOptimizerPage';
import { cardStyle, tableStyle, thStyle, tdStyle, trHoverStyle } from '../utils/styles';
import {
    formatWholeCurrency as fmt,
    formatCompactCurrency as fmtShort,
    formatPercent as pct,
} from '../utils/format';
import SpendingCorridorChart from './SpendingCorridorChart';
import PortfolioFanChart from './PortfolioFanChart';
import TaxSavingsSummary from './TaxSavingsSummary';
import ConversionScheduleTable from './ConversionScheduleTable';
import TraditionalBalanceChart from './TraditionalBalanceChart';
import NearTermSpendingGuide from './NearTermSpendingGuide';

interface OptimizerResultsViewProps {
    result: GuardrailProfileResponse;
    onReoptimize: () => void;
    retirementDate: string;
}

export default function OptimizerResultsView({
    result,
    onReoptimize,
    retirementDate,
}: OptimizerResultsViewProps) {
    const diagnostics = computePlanDiagnostics(result.phases, result.yearly_spending, result.failure_rate);
    const failureRateColors = { good: '#e8f5e9', caution: '#fff8e1', danger: '#ffebee' };
    const failureRateBorder = { good: '#a5d6a7', caution: '#ffe082', danger: '#ef9a9a' };

    // T24: `gated_on` names which success metric actually certified the recommended schedule.
    // Legacy fixtures/responses that predate the field are `undefined`, which — like the
    // explicit "no_adaptation" value — falls back to the original pre-T24 headline (Success
    // Probability certified), so old callers keep their prior card treatment unchanged.
    const hasWithRulesCard = result.success_probability_with_rules != null;
    const withRulesCertified = hasWithRulesCard && result.gated_on === 'with_rules';

    const certifiedBadgeStyle: React.CSSProperties = {
        display: 'inline-block', padding: '0.1rem 0.4rem', background: '#2e7d32', color: '#fff',
        borderRadius: '4px', fontSize: '0.65rem', fontWeight: 700, textTransform: 'uppercase',
        letterSpacing: '0.03em',
    };
    const metricLabelStyle: React.CSSProperties = {
        fontSize: '0.8rem', color: '#888', marginBottom: '0.25rem',
        display: 'flex', alignItems: 'center', justifyContent: 'center', gap: '0.35rem',
    };

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

            {result.floor_reduced && (
                <div data-testid="floor-reduced-banner" style={{
                    background: '#fff8e1', border: '1px solid #ffe082', borderRadius: '8px',
                    padding: '1rem', marginBottom: '1.5rem', color: '#e65100', fontSize: '0.9rem',
                }}>
                    Your essential floor exceeds what the portfolio can sustain at this confidence. Results measure a
                    REDUCED floor; against your original floor, success is {pct(result.original_floor_success_probability, 0)}.
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

            <div style={{
                display: 'grid',
                gridTemplateColumns: `repeat(${result.success_probability_with_rules != null ? 6 : 5}, 1fr)`,
                gap: '1rem', marginBottom: '1.5rem',
            }}>
                <div data-testid="success-probability-card" style={{
                    ...cardStyle, textAlign: 'center',
                    background: '#e8f5e9',
                    border: `1px solid ${hasWithRulesCard && !withRulesCertified ? '#2e7d32' : '#a5d6a7'}`,
                }}>
                    <div style={metricLabelStyle}>
                        {withRulesCertified ? 'If Never Adjusted' : 'Success Probability'}
                        {hasWithRulesCard && !withRulesCertified && (
                            <span data-testid="certified-badge-success-probability" style={certifiedBadgeStyle}>Certified</span>
                        )}
                    </div>
                    <div style={{ fontSize: '1.25rem', fontWeight: 700 }}>{pct(result.success_probability, 0)}</div>
                </div>
                {hasWithRulesCard && (
                    <div
                        data-testid="success-probability-with-rules-card"
                        title={withRulesCertified
                            ? "Success if you follow this profile's spending-cut rule when the portfolio falls behind — this profile gates on adaptive rules, so this is the certified number."
                            : "Success if you follow this profile's spending-cut rule when the portfolio falls behind — the no-adjustment number is the certified headline for this profile."}
                        style={{
                            ...cardStyle, textAlign: 'center',
                            background: '#e3f2fd',
                            border: `1px solid ${withRulesCertified ? '#1565c0' : '#90caf9'}`, cursor: 'help',
                        }}
                    >
                        <div style={metricLabelStyle}>
                            {withRulesCertified ? 'With Guardrail Cuts' : 'If Rules Followed'}
                            {withRulesCertified && (
                                <span data-testid="certified-badge-with-rules" style={certifiedBadgeStyle}>Certified</span>
                            )}
                        </div>
                        <div style={{ fontSize: '1.25rem', fontWeight: 700 }}>{pct(result.success_probability_with_rules, 0)}</div>
                    </div>
                )}
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
            </div>

            {result.stochastic_mortality && (
                <div data-testid="stochastic-mortality-card" style={{ ...cardStyle, marginBottom: '1.5rem' }}>
                    <h3 style={{ marginBottom: '0.5rem', marginTop: 0 }}>Longevity-Aware Success (Stochastic Mortality)</h3>
                    <p style={{ fontSize: '0.85rem', color: '#555', marginBottom: '1rem', lineHeight: 1.5 }}>
                        Instead of the fixed death ages above, this samples each spouse&apos;s death year per Monte
                        Carlo trial from an SSA mortality table. The fixed-death Success Probability above stays
                        for comparison.
                    </p>
                    <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '1rem', marginBottom: '1rem' }}>
                        <div style={{ ...cardStyle, textAlign: 'center', background: '#e8f5e9', border: '1px solid #a5d6a7' }}>
                            <div style={{ fontSize: '0.8rem', color: '#888', marginBottom: '0.25rem' }}>Lifetime Success</div>
                            <div style={{ fontSize: '1.25rem', fontWeight: 700 }}>
                                {pct(result.stochastic_mortality.lifetime_success_probability, 0)}
                            </div>
                            <div style={{ fontSize: '0.75rem', color: '#888', marginTop: '0.25rem' }}>
                                Never falls short while either spouse is alive
                            </div>
                        </div>
                        <div style={{ ...cardStyle, textAlign: 'center', background: '#e3f2fd', border: '1px solid #90caf9' }}>
                            <div style={{ fontSize: '0.8rem', color: '#888', marginBottom: '0.25rem' }}>Longevity-Conditional Success</div>
                            <div style={{ fontSize: '1.25rem', fontWeight: 700 }}>
                                {pct(result.stochastic_mortality.longevity_conditional.probability, 0)}
                            </div>
                            <div style={{ fontSize: '0.75rem', color: '#888', marginTop: '0.25rem' }}>
                                If the survivor lives to age {result.stochastic_mortality.longevity_conditional.age}
                                {' '}({pct(result.stochastic_mortality.longevity_conditional.trial_fraction, 0)} of trials)
                            </div>
                        </div>
                    </div>
                    <div style={{ fontSize: '0.85rem', color: '#555' }}>
                        <strong>Second death age (sampled):</strong>{' '}
                        P10 {result.stochastic_mortality.second_death_age.p10} &middot;{' '}
                        Median {result.stochastic_mortality.second_death_age.median} &middot;{' '}
                        P90 {result.stochastic_mortality.second_death_age.p90}
                    </div>
                </div>
            )}

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
                                <div style={{ fontSize: '0.75rem', color: '#1976d2' }}>Median (p50)</div>
                                <div style={{ fontSize: '1.1rem', fontWeight: 600 }}>{fmt(result.median_final_balance)}</div>
                            </div>
                        </div>
                        <div style={{ textAlign: 'center', fontSize: '0.75rem', color: '#888', marginTop: '0.25rem' }}>
                            Final year portfolio balance at age {lastYear.age}
                        </div>
                    </div>
                ) : null;
            })()}

            {result.fixed_return_share != null && result.fixed_return_share > 0.5 && (
                <div data-testid="fixed-return-share-note" style={{
                    padding: '0.75rem 1rem', marginBottom: '1.5rem',
                    background: '#e3f2fd', border: '1px solid #90caf9', borderRadius: '6px',
                    fontSize: '0.8rem', color: '#0d47a1',
                }}>
                    {pct(result.fixed_return_share, 0)} of this portfolio uses a fixed expected return and shows no
                    market variability.
                </div>
            )}

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
                    <table style={tableStyle}>
                        <thead>
                            <tr>
                                <th style={thStyle}>Phase</th>
                                <th style={thStyle}>Ages</th>
                                <th style={{ ...thStyle, textAlign: 'right' }}>Target</th>
                                <th style={{ ...thStyle, textAlign: 'right' }}>Avg Recommended</th>
                                <th style={{ ...thStyle, width: '35%' }}>Achievement</th>
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
                                    <tr key={p.phaseName} style={trHoverStyle}>
                                        <td style={tdStyle}>{p.phaseName}</td>
                                        <td style={{ ...tdStyle, color: '#888' }}>{ageRange}</td>
                                        <td style={{ ...tdStyle, textAlign: 'right' }}>{fmt(p.targetSpending)}</td>
                                        <td style={{ ...tdStyle, textAlign: 'right' }}>{fmt(p.avgRecommended)}</td>
                                        <td style={tdStyle}>
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
                    median (50th percentile) scenarios. The red dashed line is the worst-case floor.
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
                <table style={tableStyle}>
                    <thead>
                        <tr style={{ borderBottom: '1px solid #e0e0e0' }}>
                            <th rowSpan={2} style={{ ...thStyle, verticalAlign: 'bottom' }}>Age</th>
                            <th rowSpan={2} style={{ ...thStyle, verticalAlign: 'bottom' }}>Phase</th>
                            <th rowSpan={2} style={{ ...thStyle, textAlign: 'right', verticalAlign: 'bottom' }}>Recommended</th>
                            <th rowSpan={2} style={{ ...thStyle, textAlign: 'right', verticalAlign: 'bottom' }}>Floor</th>
                            <th rowSpan={2} style={{ ...thStyle, textAlign: 'right', verticalAlign: 'bottom' }}>Discretionary</th>
                            <th rowSpan={2} style={{ ...thStyle, textAlign: 'right', verticalAlign: 'bottom' }}>Income</th>
                            <th rowSpan={2} style={{ ...thStyle, textAlign: 'right', verticalAlign: 'bottom' }}>Portfolio Draw</th>
                            <th colSpan={3} style={{ ...thStyle, textAlign: 'center', borderBottom: '1px solid #e0e0e0' }}>Portfolio Balance</th>
                            <th rowSpan={2} style={{ ...thStyle, textAlign: 'right', verticalAlign: 'bottom' }}>Corridor</th>
                        </tr>
                        <tr style={{ borderBottom: '2px solid #e0e0e0' }}>
                            <th style={{ ...thStyle, textAlign: 'right', padding: '0.25rem 0.5rem', fontSize: '0.75rem', color: '#888' }}>p10</th>
                            <th style={{ ...thStyle, textAlign: 'right', padding: '0.25rem 0.5rem', fontSize: '0.75rem', color: '#888' }}>p25</th>
                            <th style={{ ...thStyle, textAlign: 'right', padding: '0.25rem 0.5rem', fontSize: '0.75rem', color: '#888' }}>p50</th>
                        </tr>
                    </thead>
                    <tbody>
                        {result.yearly_spending.map(y => (
                            <tr key={y.year} style={trHoverStyle}>
                                <td style={tdStyle}>{y.age}</td>
                                <td style={{ ...tdStyle, color: '#666' }}>{y.phase_name}</td>
                                <td style={{ ...tdStyle, textAlign: 'right', fontWeight: 600 }}>{fmt(y.recommended)}</td>
                                <td style={{ ...tdStyle, textAlign: 'right' }}>{fmt(y.essential_floor)}</td>
                                <td style={{ ...tdStyle, textAlign: 'right' }}>{fmt(y.discretionary)}</td>
                                <td style={{ ...tdStyle, textAlign: 'right' }}>{fmt(y.income_offset)}</td>
                                <td style={{ ...tdStyle, textAlign: 'right' }}>{fmt(y.portfolio_withdrawal)}</td>
                                <td style={{
                                    ...tdStyle, textAlign: 'right',
                                    color: y.portfolio_balance_p10 != null && y.portfolio_balance_p10 <= 0 ? '#ef5350' : '#888',
                                }}>
                                    {fmtShort(y.portfolio_balance_p10)}
                                </td>
                                <td style={{ ...tdStyle, textAlign: 'right', color: '#888' }}>
                                    {fmtShort(y.portfolio_balance_p25)}
                                </td>
                                <td style={{ ...tdStyle, textAlign: 'right', color: '#888' }}>
                                    {fmtShort(y.portfolio_balance_median)}
                                </td>
                                <td style={{ ...tdStyle, textAlign: 'right', color: '#888' }}>
                                    {fmt(y.corridor_low)} &ndash; {fmt(y.corridor_high)}
                                </td>
                            </tr>
                        ))}
                    </tbody>
                </table>
            </div>

            <div style={{ ...cardStyle, marginBottom: '1.5rem' }}>
                <h3 style={{ marginBottom: '1rem', marginTop: 0 }}>Near-Term Spending Guide</h3>
                <NearTermSpendingGuide
                    yearlySpending={result.yearly_spending}
                    retirementDate={retirementDate}
                />
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
