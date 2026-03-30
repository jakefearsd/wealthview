import { useState, useEffect } from 'react';
import { useParams, useNavigate, Link } from 'react-router';
import toast from 'react-hot-toast';
import { getScenario, optimizeSpending, getGuardrailProfile, reoptimize } from '../api/projections';
import type { Scenario, GuardrailPhase, GuardrailProfileResponse, GuardrailOptimizationRequest, GuardrailYearlySpending } from '../types/projection';
import { cardStyle, inputStyle } from '../utils/styles';
import CurrencyInput from '../components/CurrencyInput';
import FormField from '../components/FormField';
import PhaseEditor from '../components/PhaseEditor';
import OptimizerResultsView from '../components/OptimizerResultsView';

type OptimizerState = 'configure' | 'running' | 'results';
type RiskTolerance = 'conservative' | 'moderate' | 'aggressive';

export interface PhaseDiagnostic {
    phaseName: string;
    targetSpending: number;
    avgRecommended: number;
    achievementPct: number;
}

export interface PlanDiagnostics {
    phases: PhaseDiagnostic[];
    overallAchievement: number;
    warnings: string[];
    failureRateSeverity: 'good' | 'caution' | 'danger';
    depletionAgeP10: number | null;
    depletionAgeP25: number | null;
}

export function computePlanDiagnostics(
    phases: GuardrailPhase[],
    yearlySpending: GuardrailYearlySpending[],
    failureRate: number,
): PlanDiagnostics {
    const phaseDiags: PhaseDiagnostic[] = [];
    const warnings: string[] = [];

    for (const phase of phases) {
        if (phase.target_spending == null || phase.target_spending <= 0) continue;

        const phaseYears = yearlySpending.filter(y => {
            if (y.age < phase.start_age) return false;
            if (phase.end_age != null && y.age > phase.end_age) return false;
            return true;
        });

        if (phaseYears.length === 0) continue;

        const avgRecommended = phaseYears.reduce((sum, y) => sum + y.recommended, 0) / phaseYears.length;
        const achievementPct = (avgRecommended / phase.target_spending) * 100;

        phaseDiags.push({
            phaseName: phase.name,
            targetSpending: phase.target_spending,
            avgRecommended,
            achievementPct,
        });

        if (achievementPct < 90) {
            warnings.push(`${phase.name} is only ${Math.round(achievementPct)}% funded`);
        }
    }

    const overallAchievement = phaseDiags.length > 0
        ? phaseDiags.reduce((sum, p) => sum + p.achievementPct, 0) / phaseDiags.length
        : 100;

    let failureRateSeverity: 'good' | 'caution' | 'danger';
    if (failureRate > 0.20) {
        failureRateSeverity = 'danger';
        warnings.push(`Failure rate exceeds 20%`);
    } else if (failureRate > 0.10) {
        failureRateSeverity = 'caution';
    } else {
        failureRateSeverity = 'good';
    }

    // Detect portfolio depletion at p10 and p25
    let depletionAgeP10: number | null = null;
    let depletionAgeP25: number | null = null;
    for (const y of yearlySpending) {
        if (depletionAgeP10 === null && y.portfolio_balance_p10 != null && y.portfolio_balance_p10 <= 0) {
            depletionAgeP10 = y.age;
        }
        if (depletionAgeP25 === null && y.portfolio_balance_p25 != null && y.portfolio_balance_p25 <= 0) {
            depletionAgeP25 = y.age;
        }
    }
    if (depletionAgeP10 !== null) {
        warnings.push(`In a pessimistic scenario (10th percentile), portfolio depleted by age ${depletionAgeP10}`);
    }
    if (depletionAgeP25 !== null) {
        warnings.push(`In a below-average scenario (25th percentile), portfolio depleted by age ${depletionAgeP25}`);
    }

    return { phases: phaseDiags, overallAchievement, warnings, failureRateSeverity, depletionAgeP10, depletionAgeP25 };
}

const selectStyle: React.CSSProperties = {
    ...inputStyle,
    appearance: 'auto',
};

const adornmentWrapStyle: React.CSSProperties = {
    display: 'flex',
    alignItems: 'center',
    border: '1px solid #ccc',
    borderRadius: '4px',
    overflow: 'hidden',
};

const adornmentStyle: React.CSSProperties = {
    padding: '0.5rem 0.5rem',
    background: '#f5f5f5',
    color: '#666',
    fontSize: '0.9rem',
    borderRight: '1px solid #ccc',
    userSelect: 'none',
};

const adornmentSuffixStyle: React.CSSProperties = {
    ...adornmentStyle,
    borderRight: 'none',
    borderLeft: '1px solid #ccc',
};

const adornedInputStyle: React.CSSProperties = {
    ...inputStyle,
    border: 'none',
    borderRadius: 0,
    flex: 1,
};

const pillContainerStyle: React.CSSProperties = {
    display: 'flex',
    border: '1px solid #ccc',
    borderRadius: '4px',
    overflow: 'hidden',
};

function pillStyle(active: boolean): React.CSSProperties {
    return {
        flex: 1,
        padding: '0.5rem 0.75rem',
        border: 'none',
        background: active ? '#1976d2' : '#fff',
        color: active ? '#fff' : '#333',
        cursor: 'pointer',
        fontWeight: active ? 600 : 400,
        fontSize: '0.85rem',
        textTransform: 'capitalize',
        transition: 'background 0.15s, color 0.15s',
    };
}

export default function SpendingOptimizerPage() {
    const { id } = useParams<{ id: string }>();
    const navigate = useNavigate();
    const [scenario, setScenario] = useState<Scenario | null>(null);
    const [state, setState] = useState<OptimizerState>('configure');
    const [result, setResult] = useState<GuardrailProfileResponse | null>(null);

    // Main parameters
    const [name, setName] = useState('Optimized Spending Plan');
    const [essentialFloor, setEssentialFloor] = useState(30000);
    const [terminalTarget, setTerminalTarget] = useState(0);
    const [portfolioFloor, setPortfolioFloor] = useState(0);
    const [riskTolerance, setRiskTolerance] = useState<RiskTolerance>('moderate');
    const [spendingFlexibility, setSpendingFlexibility] = useState(5);
    const [phaseBlendYears, setPhaseBlendYears] = useState(1);

    // Cash buffer parameters
    const [cashReserveYears, setCashReserveYears] = useState(2);
    const [cashReturnRate, setCashReturnRate] = useState(4);

    // Roth conversion parameters
    const [optimizeConversions, setOptimizeConversions] = useState(false);
    const [conversionBracketRate, setConversionBracketRate] = useState(0.22);
    const [rmdTargetBracketRate, setRmdTargetBracketRate] = useState(0.12);
    const [exhaustionBuffer, setExhaustionBuffer] = useState(5);
    const [rmdBracketHeadroom, setRmdBracketHeadroom] = useState(10);

    // Advanced parameters (collapsed by default)
    const [showAdvanced, setShowAdvanced] = useState(false);
    const [trialCount, setTrialCount] = useState(5000);
    const [confidenceLevel, setConfidenceLevel] = useState<number | null>(null);

    const [phases, setPhases] = useState<GuardrailPhase[]>([
        { name: 'Early retirement', start_age: 62, end_age: 72, priority_weight: 3, target_spending: 80000 },
        { name: 'Mid retirement', start_age: 73, end_age: 82, priority_weight: 2, target_spending: 60000 },
        { name: 'Late retirement', start_age: 83, end_age: null, priority_weight: 1, target_spending: 45000 },
    ]);

    useEffect(() => {
        if (!id) return;
        getScenario(id).then(setScenario).catch(() => toast.error('Failed to load scenario'));
        getGuardrailProfile(id).then(profile => {
            if (profile) {
                setResult(profile);
                setState('results');
                setName(profile.name);
                setEssentialFloor(profile.essential_floor);
                setTerminalTarget(profile.terminal_balance_target);
                setTrialCount(profile.trial_count);
                setCashReserveYears(profile.cash_reserve_years ?? 2);
                setCashReturnRate(
                    profile.cash_return_rate != null
                        ? profile.cash_return_rate * 100
                        : 4
                );
                setPortfolioFloor(profile.portfolio_floor ?? 0);
                setSpendingFlexibility(
                    profile.max_annual_adjustment_rate != null
                        ? profile.max_annual_adjustment_rate * 100
                        : 5
                );
                setPhaseBlendYears(profile.phase_blend_years ?? 1);
                if (profile.risk_tolerance) {
                    setRiskTolerance(profile.risk_tolerance as RiskTolerance);
                }
                if (profile.phases.length > 0) {
                    setPhases(profile.phases);
                }
                // Restore Roth conversion strategy inputs
                if (profile.conversion_schedule) {
                    setOptimizeConversions(true);
                    setConversionBracketRate(profile.conversion_schedule.conversion_bracket_rate);
                    setRmdTargetBracketRate(profile.conversion_schedule.rmd_target_bracket_rate);
                    setExhaustionBuffer(profile.conversion_schedule.traditional_exhaustion_buffer);
                    if (profile.conversion_schedule.rmd_bracket_headroom != null) {
                        setRmdBracketHeadroom(Math.round(profile.conversion_schedule.rmd_bracket_headroom * 100));
                    }
                }
            }
        });
    }, [id]);

    const handleOptimize = async () => {
        if (!id) return;
        setState('running');
        try {
            const request: GuardrailOptimizationRequest = {
                scenario_id: id,
                name,
                essential_floor: essentialFloor,
                terminal_balance_target: terminalTarget,
                trial_count: trialCount,
                cash_reserve_years: cashReserveYears,
                cash_return_rate: cashReturnRate / 100,
                phases,
                portfolio_floor: portfolioFloor,
                max_annual_adjustment_rate: spendingFlexibility / 100,
                phase_blend_years: phaseBlendYears,
                risk_tolerance: riskTolerance,
                ...(confidenceLevel != null ? { confidence_level: confidenceLevel / 100 } : {}),
                ...(optimizeConversions ? {
                    optimize_conversions: true,
                    conversion_bracket_rate: conversionBracketRate,
                    rmd_target_bracket_rate: rmdTargetBracketRate,
                    traditional_exhaustion_buffer: exhaustionBuffer,
                    rmd_bracket_headroom: rmdBracketHeadroom / 100,
                } : {}),
            };
            const profile = await optimizeSpending(id, request);
            setResult(profile);
            setState('results');
            toast.success('Optimization complete');
        } catch {
            setState('configure');
            toast.error('Optimization failed');
        }
    };

    const handleReoptimize = async () => {
        if (!id) return;
        setState('running');
        try {
            const profile = await reoptimize(id);
            setResult(profile);
            setState('results');
            toast.success('Re-optimization complete');
        } catch {
            setState('results');
            toast.error('Re-optimization failed');
        }
    };

    const fmt = (n: number | null | undefined) => n != null ? n.toLocaleString('en-US', { style: 'currency', currency: 'USD', maximumFractionDigits: 0 }) : '--';
    const fmtShort = (n: number | null | undefined) => {
        if (n == null) return '--';
        const abs = Math.abs(n);
        const sign = n < 0 ? '-' : '';
        if (abs >= 1_000_000) return `${sign}$${(abs / 1_000_000).toFixed(1)}M`;
        if (abs >= 1_000) return `${sign}$${Math.round(abs / 1_000)}k`;
        return `${sign}$${Math.round(abs)}`;
    };
    const pct = (n: number | null | undefined) => n != null ? `${(n * 100).toFixed(1)}%` : '--';

    if (!scenario) {
        return <div>Loading scenario...</div>;
    }

    return (
        <div>
            <div style={{ marginBottom: '1rem' }}>
                <Link to={`/projections/${id}`} style={{ color: '#1976d2', textDecoration: 'none', fontSize: '0.85rem' }}>
                    &larr; Back to {scenario.name}
                </Link>
            </div>

            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '0.75rem' }}>
                <h2 style={{ margin: 0 }}>Spending Optimizer</h2>
                <div style={{ display: 'flex', gap: '0.5rem' }}>
                    {state === 'configure' && (
                        <button onClick={handleOptimize}
                            style={{ padding: '0.5rem 1rem', background: '#7c3aed', color: '#fff', border: 'none', borderRadius: '4px', cursor: 'pointer' }}>
                            Run Optimization
                        </button>
                    )}
                    {state === 'results' && (
                        <>
                            <button onClick={() => setState('configure')}
                                style={{ padding: '0.5rem 1rem', background: '#ff9800', color: '#fff', border: 'none', borderRadius: '4px', cursor: 'pointer' }}>
                                Adjust &amp; Re-run
                            </button>
                            <button onClick={() => navigate(`/projections/${id}`)}
                                style={{ padding: '0.5rem 1rem', background: '#1976d2', color: '#fff', border: 'none', borderRadius: '4px', cursor: 'pointer' }}>
                                Back to Scenario
                            </button>
                        </>
                    )}
                </div>
            </div>
            <div style={{
                background: '#f8f9fa', border: '1px solid #e0e0e0', borderRadius: '6px',
                padding: '0.75rem 1rem', marginBottom: '1.5rem', fontSize: '0.85rem',
            }}>
                <div style={{ marginBottom: '0.35rem' }}>
                    <strong style={{ color: '#666' }}>Scenario:</strong>{' '}
                    <Link to={`/projections/${id}`} style={{ color: '#1976d2', textDecoration: 'none' }}>{scenario.name}</Link>
                </div>
                <div style={{ display: 'flex', gap: '1.5rem', color: '#555', flexWrap: 'wrap', marginBottom: '0.35rem' }}>
                    <span><strong style={{ color: '#666' }}>Inflation:</strong> {(scenario.inflation_rate * 100).toFixed(1)}%</span>
                    <span><strong style={{ color: '#666' }}>Retirement:</strong> {new Date(scenario.retirement_date).getFullYear()}</span>
                    <span><strong style={{ color: '#666' }}>End Age:</strong> {scenario.end_age}</span>
                </div>
                {scenario.accounts.length > 0 && (
                    <div style={{ color: '#555', marginBottom: '0.35rem' }}>
                        <strong style={{ color: '#666' }}>Accounts:</strong>{' '}
                        {Object.entries(
                            scenario.accounts.reduce<Record<string, number>>((acc, a) => {
                                const type = a.account_type.charAt(0).toUpperCase() + a.account_type.slice(1);
                                acc[type] = (acc[type] ?? 0) + a.initial_balance;
                                return acc;
                            }, {})
                        ).map(([type, balance], i, arr) => (
                            <span key={type}>
                                {balance.toLocaleString('en-US', { style: 'currency', currency: 'USD', maximumFractionDigits: 0 })} {type}
                                {i < arr.length - 1 ? ' · ' : ''}
                            </span>
                        ))}
                    </div>
                )}
                {scenario.income_sources.length > 0 && (
                    <div style={{ color: '#555', marginBottom: '0.35rem' }}>
                        <strong style={{ color: '#666' }}>Income:</strong>{' '}
                        {scenario.income_sources.slice(0, 3).map((src, i, arr) => (
                            <span key={src.income_source_id}>
                                {src.name} ({src.effective_amount.toLocaleString('en-US', { style: 'currency', currency: 'USD', maximumFractionDigits: 0 })})
                                {i < arr.length - 1 ? ' · ' : ''}
                            </span>
                        ))}
                        {scenario.income_sources.length > 3 && (
                            <span> and {scenario.income_sources.length - 3} more</span>
                        )}
                    </div>
                )}
                <div style={{ fontSize: '0.75rem', color: '#999', marginTop: '0.25rem' }}>
                    These values come from the projection scenario. Edit the scenario to change them.
                </div>
            </div>

            {state === 'configure' && (
                <div>
                    <div style={{ ...cardStyle, marginBottom: '1.5rem' }}>
                        <h3 style={{ marginBottom: '1rem' }}>Optimization Parameters</h3>
                        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr 1fr', gap: '1rem' }}>
                            <FormField label="Profile Name">
                                <input style={inputStyle} type="text" value={name}
                                    onChange={e => setName(e.target.value)} />
                            </FormField>
                            <FormField label="Essential Spending Floor (per year)">
                                <div style={adornmentWrapStyle}>
                                    <span style={adornmentStyle}>$</span>
                                    <CurrencyInput
                                        style={adornedInputStyle}
                                        value={essentialFloor || ''}
                                        onChange={v => setEssentialFloor(v === '' ? 0 : Number(v))}
                                    />
                                </div>
                            </FormField>
                            <FormField label="Terminal Balance Target">
                                <div style={adornmentWrapStyle}>
                                    <span style={adornmentStyle}>$</span>
                                    <CurrencyInput
                                        style={adornedInputStyle}
                                        value={terminalTarget || ''}
                                        onChange={v => setTerminalTarget(v === '' ? 0 : Number(v))}
                                    />
                                </div>
                            </FormField>
                            <FormField label="Portfolio Safety Net" helpText="Minimum portfolio balance to maintain during retirement">
                                <div style={adornmentWrapStyle}>
                                    <span style={adornmentStyle}>$</span>
                                    <CurrencyInput
                                        style={adornedInputStyle}
                                        value={portfolioFloor || ''}
                                        onChange={v => setPortfolioFloor(v === '' ? 0 : Number(v))}
                                    />
                                </div>
                            </FormField>
                            <FormField label="Risk Tolerance" helpText={
                                riskTolerance === 'conservative' ? '85% confidence \u2014 Very likely sustainable without adjustments'
                                : riskTolerance === 'moderate' ? '70% confidence \u2014 Sustainable with occasional adjustments in bad markets'
                                : '60% confidence \u2014 Expected spending, requires active management in downturns'
                            }>
                                <div style={pillContainerStyle}>
                                    {(['conservative', 'moderate', 'aggressive'] as RiskTolerance[]).map(level => (
                                        <button key={level} type="button"
                                            onClick={() => setRiskTolerance(level)}
                                            style={pillStyle(riskTolerance === level)}>
                                            {level}
                                        </button>
                                    ))}
                                </div>
                            </FormField>
                            <FormField label="Spending Flexibility" helpText="Maximum annual spending change">
                                <div style={adornmentWrapStyle}>
                                    <input style={adornedInputStyle} type="number" step="1" min="0" max="50"
                                        value={spendingFlexibility || ''}
                                        onChange={e => setSpendingFlexibility(Number(e.target.value))} />
                                    <span style={adornmentSuffixStyle}>%/yr</span>
                                </div>
                            </FormField>
                        </div>

                        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr 1fr', gap: '1rem', marginTop: '1rem' }}>
                            <FormField label="Phase Blending" helpText="Smooth transitions between life phases">
                                <select style={selectStyle} value={phaseBlendYears}
                                    onChange={e => setPhaseBlendYears(Number(e.target.value))}>
                                    <option value={0}>Off</option>
                                    <option value={1}>1 year</option>
                                    <option value={2}>2 years</option>
                                </select>
                            </FormField>
                        </div>

                        {/* Advanced Settings */}
                        <div style={{ borderTop: '1px solid #eee', marginTop: '1.5rem', paddingTop: '1rem' }}>
                            <button type="button" onClick={() => setShowAdvanced(!showAdvanced)}
                                style={{ background: 'none', border: 'none', cursor: 'pointer', color: '#888', fontSize: '0.85rem', padding: 0 }}>
                                <span style={{ display: 'inline-block', transition: 'transform 0.15s', transform: showAdvanced ? 'rotate(90deg)' : 'none' }}>&rsaquo;</span>
                                {' '}Advanced Settings
                            </button>
                            {showAdvanced && (
                                <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr 1fr 1fr', gap: '1rem', marginTop: '0.75rem' }}>
                                    <FormField label="Cash Reserve" helpText="Years of spending held in cash to avoid selling during downturns">
                                        <select style={selectStyle} value={cashReserveYears}
                                            onChange={e => setCashReserveYears(Number(e.target.value))}>
                                            <option value={0}>0 years</option>
                                            <option value={1}>1 year</option>
                                            <option value={2}>2 years</option>
                                            <option value={3}>3 years</option>
                                        </select>
                                    </FormField>
                                    <FormField label="Cash Rate" helpText="Expected annual return on cash reserves (money market rate)">
                                        <div style={adornmentWrapStyle}>
                                            <input style={adornedInputStyle} type="number" step="0.1" value={cashReturnRate || ''}
                                                onChange={e => setCashReturnRate(Number(e.target.value))} />
                                            <span style={adornmentSuffixStyle}>%</span>
                                        </div>
                                    </FormField>
                                    <FormField label="Trial Count">
                                        <select style={selectStyle} value={trialCount}
                                            onChange={e => setTrialCount(Number(e.target.value))}>
                                            <option value={1000}>1,000</option>
                                            <option value={2500}>2,500</option>
                                            <option value={5000}>5,000</option>
                                            <option value={10000}>10,000</option>
                                        </select>
                                    </FormField>
                                    <FormField label="Confidence Level" helpText="Override for risk tolerance">
                                        <div style={adornmentWrapStyle}>
                                            <input style={adornedInputStyle} type="number" step="1" min="50" max="99"
                                                value={confidenceLevel ?? ''}
                                                placeholder="Uses risk tolerance"
                                                onChange={e => setConfidenceLevel(e.target.value ? Number(e.target.value) : null)} />
                                            <span style={adornmentSuffixStyle}>%</span>
                                        </div>
                                    </FormField>
                                </div>
                            )}
                        </div>
                    </div>

                    <div style={{ ...cardStyle, marginBottom: '1.5rem' }}>
                        <PhaseEditor phases={phases} onPhasesChange={setPhases} />
                    </div>

                    <div style={{ ...cardStyle, marginBottom: '1.5rem' }}>
                        <label style={{ display: 'flex', alignItems: 'center', gap: '0.5rem', cursor: 'pointer', marginBottom: optimizeConversions ? '1rem' : 0 }}>
                            <input type="checkbox" checked={optimizeConversions}
                                onChange={e => setOptimizeConversions(e.target.checked)} />
                            <h3 style={{ margin: 0 }}>Roth Conversion Strategy</h3>
                        </label>
                        {optimizeConversions && (
                            <div>
                                <div style={{ fontSize: '0.8rem', color: '#666', marginBottom: '1rem' }}>
                                    Optimize Roth conversions alongside spending to minimize lifetime taxes.
                                    Conversions shift money from Traditional to Roth accounts, paying tax now at a
                                    lower bracket to avoid higher RMD-driven taxes later.
                                </div>
                                <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr 1fr', gap: '1rem' }}>
                                    <FormField label="Conversion Bracket" helpText="Maximum tax bracket to fill with conversions each year">
                                        <select style={selectStyle} value={conversionBracketRate}
                                            onChange={e => {
                                                const rate = Number(e.target.value);
                                                setConversionBracketRate(rate);
                                                if (rmdTargetBracketRate > rate) {
                                                    setRmdTargetBracketRate(rate);
                                                }
                                            }}>
                                            <option value={0.10}>10%</option>
                                            <option value={0.12}>12%</option>
                                            <option value={0.22}>22%</option>
                                            <option value={0.24}>24%</option>
                                            <option value={0.32}>32%</option>
                                            <option value={0.35}>35%</option>
                                            <option value={0.37}>37%</option>
                                        </select>
                                    </FormField>
                                    <FormField label="RMD Target Bracket" helpText="Target bracket for RMDs after conversions are complete">
                                        <select style={selectStyle} value={rmdTargetBracketRate}
                                            onChange={e => setRmdTargetBracketRate(Number(e.target.value))}>
                                            {[0.10, 0.12, 0.22, 0.24, 0.32, 0.35, 0.37]
                                                .filter(r => r <= conversionBracketRate)
                                                .map(r => (
                                                    <option key={r} value={r}>{(r * 100).toFixed(0)}%</option>
                                                ))}
                                        </select>
                                    </FormField>
                                    <FormField label="RMD Bracket Headroom" helpText="Reserve headroom for market growth years. Higher = more conservative.">
                                        <div style={adornmentWrapStyle}>
                                            <input style={adornedInputStyle} type="number" step="1" min="5" max="25"
                                                value={rmdBracketHeadroom || ''}
                                                onChange={e => setRmdBracketHeadroom(Number(e.target.value))} />
                                            <span style={adornmentSuffixStyle}>%</span>
                                        </div>
                                    </FormField>
                                </div>
                            </div>
                        )}
                    </div>

                </div>
            )}

            {state === 'running' && (
                <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', padding: '5rem 0' }}>
                    <div style={{
                        width: '3rem', height: '3rem', border: '3px solid #e0e0e0',
                        borderTopColor: '#1976d2', borderRadius: '50%',
                        animation: 'spin 1s linear infinite', marginBottom: '1rem',
                    }} />
                    <style>{`@keyframes spin { to { transform: rotate(360deg); } }`}</style>
                    <div style={{ color: '#666' }}>Running {trialCount.toLocaleString()} Monte Carlo trials...</div>
                </div>
            )}

            {state === 'results' && result && (
                <OptimizerResultsView
                    result={result}
                    onReoptimize={handleReoptimize}
                    fmt={fmt}
                    fmtShort={fmtShort}
                    pct={pct}
                    retirementDate={scenario.retirement_date}
                />
            )}
        </div>
    );
}
