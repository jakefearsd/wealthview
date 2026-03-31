import type { GuardrailYearlySpending } from '../types/projection';
import { formatDollarTooltip } from '../utils/chartFormatters';

interface Props {
    yearlySpending: GuardrailYearlySpending[];
    retirementDate: string;
}

const NEAR_TERM_YEARS = 5;

function fmt(value: number | null | undefined): string {
    if (value == null) return '—';
    return formatDollarTooltip(value);
}

interface YoYDelta {
    amount: number;
    percent: number;
}

function computeDelta(current: number, previous: number): YoYDelta | null {
    if (previous === 0) return null;
    const amount = current - previous;
    const percent = (amount / previous) * 100;
    return { amount, percent };
}

function DeltaBadge({ delta }: { delta: YoYDelta | null }) {
    if (delta == null || Math.abs(delta.amount) < 100) return null;
    const isPositive = delta.amount >= 0;
    const color = isPositive ? '#2e7d32' : '#c62828';
    const arrow = isPositive ? '\u2191' : '\u2193';
    return (
        <span style={{ fontSize: '0.75rem', color, fontWeight: 600 }}>
            {arrow} {fmt(Math.abs(delta.amount))} ({delta.percent >= 0 ? '+' : ''}{delta.percent.toFixed(1)}%)
        </span>
    );
}

function PhaseTransitionBadge({ from, to }: { from: string; to: string }) {
    return (
        <div data-testid="phase-transition" style={{
            display: 'inline-block', padding: '0.2rem 0.5rem', borderRadius: '4px',
            background: '#e8eaf6', color: '#3949ab', fontSize: '0.75rem', fontWeight: 600,
        }}>
            Phase: {from} &rarr; {to}
        </div>
    );
}

function SpendingByPortfolio({ year, recommended }: { year: GuardrailYearlySpending; recommended: number }) {
    if (year.portfolio_balance_p25 == null && year.portfolio_balance_median == null) return null;

    const p25Bal = year.portfolio_balance_p25 ?? 0;
    const medianBal = year.portfolio_balance_median ?? 0;

    const p50FourPct = medianBal * 0.04;
    const p50Spending = Math.max(recommended, p50FourPct);
    const p50UsesHeuristic = p50FourPct > recommended;

    return (
        <div data-testid="spending-by-portfolio" style={{ display: 'flex', flexDirection: 'column', gap: '0.35rem' }}>
            {year.portfolio_balance_p25 != null && (
                <div style={{
                    display: 'flex', justifyContent: 'space-between', alignItems: 'center',
                    padding: '0.5rem 0.6rem', borderRadius: '6px', background: '#fff8f8',
                }}>
                    <div>
                        <div style={{ fontSize: '0.8rem', fontWeight: 600, color: '#555' }}>Recommended (p25)</div>
                        <div style={{ fontSize: '0.7rem', color: '#888' }}>
                            Portfolio at {fmt(p25Bal)}
                        </div>
                    </div>
                    <div style={{ fontSize: '1rem', fontWeight: 700, color: '#c62828' }}>
                        {fmt(recommended)}
                    </div>
                </div>
            )}
            {year.portfolio_balance_median != null && (
                <div style={{
                    display: 'flex', justifyContent: 'space-between', alignItems: 'center',
                    padding: '0.5rem 0.6rem', borderRadius: '6px', background: '#f5f9ff',
                }}>
                    <div>
                        <div style={{ fontSize: '0.8rem', fontWeight: 600, color: '#555' }}>Expected path (p50)</div>
                        <div style={{ fontSize: '0.7rem', color: '#888' }}>
                            Portfolio at {fmt(medianBal)}
                            {p50UsesHeuristic && <span> &middot; 4% of portfolio</span>}
                        </div>
                    </div>
                    <div style={{ fontSize: '1rem', fontWeight: 700, color: '#1565c0' }}>
                        {fmt(p50Spending)}
                    </div>
                </div>
            )}
        </div>
    );
}

function CompactSpendingByPortfolio({ year, recommended }: { year: GuardrailYearlySpending; recommended: number }) {
    if (year.portfolio_balance_p25 == null && year.portfolio_balance_median == null) return null;

    const p25Bal = year.portfolio_balance_p25 ?? 0;
    const medianBal = year.portfolio_balance_median ?? 0;
    const p50Spending = Math.max(recommended, medianBal * 0.04);

    return (
        <div data-testid="spending-by-portfolio" style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '0.5rem', marginTop: '0.5rem' }}>
            {year.portfolio_balance_p25 != null && (
                <div style={{ background: '#fff8f8', borderRadius: '4px', padding: '0.35rem 0.5rem', textAlign: 'center' }}>
                    <div style={{ fontSize: '0.65rem', color: '#888' }}>Recommended (p25)</div>
                    <div style={{ fontSize: '0.7rem', color: '#888' }}>{fmt(p25Bal)}</div>
                    <div style={{ fontSize: '0.85rem', fontWeight: 600, color: '#c62828' }}>{fmt(recommended)}</div>
                </div>
            )}
            {year.portfolio_balance_median != null && (
                <div style={{ background: '#f5f9ff', borderRadius: '4px', padding: '0.35rem 0.5rem', textAlign: 'center' }}>
                    <div style={{ fontSize: '0.65rem', color: '#888' }}>Expected (p50)</div>
                    <div style={{ fontSize: '0.7rem', color: '#888' }}>{fmt(medianBal)}</div>
                    <div style={{ fontSize: '0.85rem', fontWeight: 600, color: '#1565c0' }}>{fmt(p50Spending)}</div>
                </div>
            )}
        </div>
    );
}

export default function NearTermSpendingGuide({ yearlySpending, retirementDate }: Props) {
    const nearTermYears = yearlySpending.slice(0, NEAR_TERM_YEARS);
    if (nearTermYears.length === 0) return null;

    const currentYear = new Date().getFullYear();
    const retirementYear = new Date(retirementDate).getFullYear();
    const isPreRetirement = currentYear < retirementYear;
    const yearsUntilRetirement = retirementYear - currentYear;

    return (
        <div>
            <p style={{ fontSize: '0.875rem', color: '#444', lineHeight: 1.6, marginBottom: '1rem', marginTop: 0 }}>
                {isPreRetirement ? (
                    <>
                        You retire in <strong>{yearsUntilRetirement} year{yearsUntilRetirement !== 1 ? 's' : ''}</strong>.
                        {' '}Here is your spending guide for the first {nearTermYears.length} year{nearTermYears.length !== 1 ? 's' : ''} of
                        retirement — use it to set savings targets and preview what&apos;s ahead.
                    </>
                ) : (
                    <>
                        Your spending guide for the next {nearTermYears.length} year{nearTermYears.length !== 1 ? 's' : ''}.
                        {' '}Monitor your portfolio balance against the projected ranges and adjust spending accordingly.
                    </>
                )}
            </p>

            <div style={{ display: 'flex', flexDirection: 'column', gap: '1rem' }}>
                {nearTermYears.map((year, index) => {
                    const isHero = index === 0;
                    const prevYear = index > 0 ? nearTermYears[index - 1] : null;
                    const delta = prevYear ? computeDelta(year.recommended, prevYear.recommended) : null;
                    const phaseChanged = prevYear && year.phase_name !== prevYear.phase_name;

                    if (isHero) {
                        return (
                            <div key={year.year} data-testid="hero-card" style={{
                                background: '#f5f9ff', border: '2px solid #1976d2', borderRadius: '10px',
                                padding: '1.25rem',
                            }}>
                                <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'baseline', marginBottom: '0.75rem' }}>
                                    <div>
                                        <span style={{ fontSize: '1rem', fontWeight: 700, color: '#1976d2' }}>
                                            Year 1
                                        </span>
                                        <span style={{ fontSize: '0.85rem', color: '#666', marginLeft: '0.5rem' }}>
                                            Age {year.age} &middot; {year.year}
                                        </span>
                                    </div>
                                    <span style={{ fontSize: '0.75rem', color: '#888' }}>{year.phase_name}</span>
                                </div>

                                <div style={{ fontSize: '1.75rem', fontWeight: 700, color: '#1a1a1a', marginBottom: '0.5rem' }}>
                                    {fmt(year.recommended)}
                                    <span style={{ fontSize: '0.85rem', fontWeight: 400, color: '#888', marginLeft: '0.5rem' }}>
                                        recommended
                                    </span>
                                </div>

                                <div style={{
                                    display: 'grid', gridTemplateColumns: '1fr 1fr 1fr 1fr',
                                    gap: '0.75rem', marginBottom: '1rem',
                                }}>
                                    <div>
                                        <div style={{ fontSize: '0.7rem', color: '#888', textTransform: 'uppercase' }}>Essential</div>
                                        <div style={{ fontSize: '0.95rem', fontWeight: 600 }}>{fmt(year.essential_floor)}</div>
                                    </div>
                                    <div>
                                        <div style={{ fontSize: '0.7rem', color: '#888', textTransform: 'uppercase' }}>Discretionary</div>
                                        <div style={{ fontSize: '0.95rem', fontWeight: 600 }}>{fmt(year.discretionary)}</div>
                                    </div>
                                    <div>
                                        <div style={{ fontSize: '0.7rem', color: '#888', textTransform: 'uppercase' }}>Income</div>
                                        <div style={{ fontSize: '0.95rem', fontWeight: 600 }}>{fmt(year.income_offset)}</div>
                                    </div>
                                    <div>
                                        <div style={{ fontSize: '0.7rem', color: '#888', textTransform: 'uppercase' }}>Portfolio Draw</div>
                                        <div style={{ fontSize: '0.95rem', fontWeight: 600 }}>{fmt(year.portfolio_withdrawal)}</div>
                                    </div>
                                </div>

                                <div style={{ fontSize: '0.8rem', fontWeight: 600, color: '#555', marginBottom: '0.5rem' }}>
                                    If your portfolio outperforms:
                                </div>
                                <SpendingByPortfolio year={year} recommended={year.recommended} />
                            </div>
                        );
                    }

                    return (
                        <div key={year.year} data-testid="compact-card" style={{
                            background: '#fff', border: '1px solid #e0e0e0', borderRadius: '8px',
                            padding: '1rem',
                        }}>
                            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'baseline', marginBottom: '0.5rem' }}>
                                <div>
                                    <span style={{ fontSize: '0.9rem', fontWeight: 700, color: '#333' }}>
                                        Year {index + 1}
                                    </span>
                                    <span style={{ fontSize: '0.8rem', color: '#666', marginLeft: '0.5rem' }}>
                                        Age {year.age} &middot; {year.year}
                                    </span>
                                </div>
                                <span style={{ fontSize: '0.75rem', color: '#888' }}>{year.phase_name}</span>
                            </div>

                            {phaseChanged && prevYear && (
                                <div style={{ marginBottom: '0.5rem' }}>
                                    <PhaseTransitionBadge from={prevYear.phase_name} to={year.phase_name} />
                                </div>
                            )}

                            <div style={{ marginBottom: '0.5rem' }}>
                                <span style={{ fontSize: '1.2rem', fontWeight: 700, color: '#1a1a1a' }}>
                                    {fmt(year.recommended)}
                                </span>
                                <span style={{ fontSize: '0.85rem', color: '#888', marginLeft: '0.25rem' }}>recommended</span>
                                {delta && (
                                    <span style={{ marginLeft: '0.5rem' }}>
                                        <DeltaBadge delta={delta} />
                                    </span>
                                )}
                            </div>

                            <CompactSpendingByPortfolio year={year} recommended={year.recommended} />
                        </div>
                    );
                })}
            </div>
        </div>
    );
}
