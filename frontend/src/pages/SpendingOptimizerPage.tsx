import { useState, useEffect, useRef } from 'react';
import { useParams, useNavigate, Link } from 'react-router';
import toast from 'react-hot-toast';
import { getScenario, optimizeSpending, getGuardrailProfile, reoptimize } from '../api/projections';
import type { Scenario, GuardrailPhase, GuardrailProfileResponse, GuardrailOptimizationRequest, GuardrailYearlySpending } from '../types/projection';
import { cardStyle, inputStyle, labelStyle } from '../utils/styles';
import SpendingCorridorChart from '../components/SpendingCorridorChart';
import PortfolioFanChart from '../components/PortfolioFanChart';
import TaxSavingsSummary from '../components/TaxSavingsSummary';
import ConversionScheduleTable from '../components/ConversionScheduleTable';
import TraditionalBalanceChart from '../components/TraditionalBalanceChart';

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

const smallInputStyle: React.CSSProperties = {
    padding: '0.35rem 0.5rem',
    border: '1px solid #ccc',
    borderRadius: '4px',
    width: '5rem',
};

const phaseNameInputStyle: React.CSSProperties = {
    padding: '0.35rem 0.5rem',
    border: '1px solid #ccc',
    borderRadius: '4px',
    flex: 1,
    minWidth: '8rem',
};

const smallLabelStyle: React.CSSProperties = {
    fontSize: '0.75rem',
    color: '#888',
    marginRight: '0.25rem',
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

const smallAdornmentWrapStyle: React.CSSProperties = {
    display: 'flex',
    alignItems: 'center',
    border: '1px solid #ccc',
    borderRadius: '4px',
    overflow: 'hidden',
    width: '8rem',
};

const smallAdornmentStyle: React.CSSProperties = {
    padding: '0.35rem 0.4rem',
    background: '#f5f5f5',
    color: '#666',
    fontSize: '0.8rem',
    borderRight: '1px solid #ccc',
    userSelect: 'none',
};

const smallAdornedInputStyle: React.CSSProperties = {
    padding: '0.35rem 0.5rem',
    border: 'none',
    borderRadius: 0,
    flex: 1,
    width: '100%',
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

    const addPhase = () => {
        const lastEnd = phases.length > 0
            ? (phases[phases.length - 1].end_age ?? phases[phases.length - 1].start_age + 10)
            : 62;
        setPhases([...phases, {
            name: `Phase ${phases.length + 1}`,
            start_age: lastEnd + 1,
            end_age: null,
            priority_weight: 2,
            target_spending: 50000,
        }]);
    };

    const removePhase = (index: number) => {
        setPhases(phases.filter((_, i) => i !== index));
    };

    const updatePhase = (index: number, field: keyof GuardrailPhase, value: string | number | null) => {
        const updated = [...phases];
        updated[index] = { ...updated[index], [field]: value };
        setPhases(updated);
    };

    // Drag-and-drop reordering
    const dragIndexRef = useRef<number | null>(null);
    const [dragOverIndex, setDragOverIndex] = useState<number | null>(null);

    const handleDragStart = (index: number) => {
        dragIndexRef.current = index;
    };

    const handleDragOver = (e: React.DragEvent, index: number) => {
        e.preventDefault();
        setDragOverIndex(index);
    };

    const handleDrop = (e: React.DragEvent, dropIndex: number) => {
        e.preventDefault();
        const fromIndex = dragIndexRef.current;
        if (fromIndex === null || fromIndex === dropIndex) {
            dragIndexRef.current = null;
            setDragOverIndex(null);
            return;
        }
        const updated = [...phases];
        const [moved] = updated.splice(fromIndex, 1);
        updated.splice(dropIndex, 0, moved);
        setPhases(updated);
        dragIndexRef.current = null;
        setDragOverIndex(null);
    };

    const handleDragEnd = () => {
        dragIndexRef.current = null;
        setDragOverIndex(null);
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
                            <div>
                                <label style={labelStyle}>Profile Name</label>
                                <input style={inputStyle} type="text" value={name}
                                    onChange={e => setName(e.target.value)} />
                            </div>
                            <div>
                                <label style={labelStyle}>Essential Spending Floor (per year)</label>
                                <div style={adornmentWrapStyle}>
                                    <span style={adornmentStyle}>$</span>
                                    <input style={adornedInputStyle} type="text" inputMode="numeric"
                                        value={essentialFloor.toLocaleString('en-US')}
                                        onChange={e => { const v = Number(e.target.value.replace(/[^0-9]/g, '')); if (!isNaN(v)) setEssentialFloor(v); }} />
                                </div>
                            </div>
                            <div>
                                <label style={labelStyle}>Terminal Balance Target</label>
                                <div style={adornmentWrapStyle}>
                                    <span style={adornmentStyle}>$</span>
                                    <input style={adornedInputStyle} type="text" inputMode="numeric"
                                        value={terminalTarget.toLocaleString('en-US')}
                                        onChange={e => { const v = Number(e.target.value.replace(/[^0-9]/g, '')); if (!isNaN(v)) setTerminalTarget(v); }} />
                                </div>
                            </div>
                            <div>
                                <label style={labelStyle}>Portfolio Safety Net</label>
                                <div style={adornmentWrapStyle}>
                                    <span style={adornmentStyle}>$</span>
                                    <input style={adornedInputStyle} type="text" inputMode="numeric"
                                        value={portfolioFloor.toLocaleString('en-US')}
                                        onChange={e => { const v = Number(e.target.value.replace(/[^0-9]/g, '')); if (!isNaN(v)) setPortfolioFloor(v); }} />
                                </div>
                                <div style={{ fontSize: '0.75rem', color: '#888', marginTop: '0.25rem' }}>
                                    Minimum portfolio balance to maintain during retirement
                                </div>
                            </div>
                            <div>
                                <label style={labelStyle}>Risk Tolerance</label>
                                <div style={pillContainerStyle}>
                                    {(['conservative', 'moderate', 'aggressive'] as RiskTolerance[]).map(level => (
                                        <button key={level} type="button"
                                            onClick={() => setRiskTolerance(level)}
                                            style={pillStyle(riskTolerance === level)}>
                                            {level}
                                        </button>
                                    ))}
                                </div>
                                <div style={{ fontSize: '0.75rem', color: '#888', marginTop: '0.25rem' }}>
                                    {riskTolerance === 'conservative' && '90% confidence \u2014 lowest failure risk'}
                                    {riskTolerance === 'moderate' && '80% confidence \u2014 balanced approach'}
                                    {riskTolerance === 'aggressive' && '70% confidence \u2014 higher spending, more risk'}
                                </div>
                            </div>
                            <div>
                                <label style={labelStyle}>Spending Flexibility</label>
                                <div style={adornmentWrapStyle}>
                                    <input style={adornedInputStyle} type="number" step="1" min="0" max="50"
                                        value={spendingFlexibility}
                                        onChange={e => setSpendingFlexibility(Number(e.target.value))} />
                                    <span style={adornmentSuffixStyle}>%/yr</span>
                                </div>
                                <div style={{ fontSize: '0.75rem', color: '#888', marginTop: '0.25rem' }}>
                                    Maximum annual spending change
                                </div>
                            </div>
                        </div>

                        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr 1fr', gap: '1rem', marginTop: '1rem' }}>
                            <div>
                                <label style={labelStyle}>Phase Blending</label>
                                <select style={selectStyle} value={phaseBlendYears}
                                    onChange={e => setPhaseBlendYears(Number(e.target.value))}>
                                    <option value={0}>Off</option>
                                    <option value={1}>1 year</option>
                                    <option value={2}>2 years</option>
                                </select>
                                <div style={{ fontSize: '0.75rem', color: '#888', marginTop: '0.25rem' }}>
                                    Smooth transitions between life phases
                                </div>
                            </div>
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
                                    <div>
                                        <label style={labelStyle}>Cash Reserve</label>
                                        <select style={selectStyle} value={cashReserveYears}
                                            onChange={e => setCashReserveYears(Number(e.target.value))}>
                                            <option value={0}>0 years</option>
                                            <option value={1}>1 year</option>
                                            <option value={2}>2 years</option>
                                            <option value={3}>3 years</option>
                                        </select>
                                        <div style={{ fontSize: '0.75rem', color: '#888', marginTop: '0.25rem' }}>
                                            Years of spending held in cash to avoid selling during downturns
                                        </div>
                                    </div>
                                    <div>
                                        <label style={labelStyle}>Cash Rate</label>
                                        <div style={adornmentWrapStyle}>
                                            <input style={adornedInputStyle} type="number" step="0.1" value={cashReturnRate}
                                                onChange={e => setCashReturnRate(Number(e.target.value))} />
                                            <span style={adornmentSuffixStyle}>%</span>
                                        </div>
                                        <div style={{ fontSize: '0.75rem', color: '#888', marginTop: '0.25rem' }}>
                                            Expected annual return on cash reserves (money market rate)
                                        </div>
                                    </div>
                                    <div>
                                        <label style={labelStyle}>Trial Count</label>
                                        <select style={selectStyle} value={trialCount}
                                            onChange={e => setTrialCount(Number(e.target.value))}>
                                            <option value={1000}>1,000</option>
                                            <option value={2500}>2,500</option>
                                            <option value={5000}>5,000</option>
                                            <option value={10000}>10,000</option>
                                        </select>
                                    </div>
                                    <div>
                                        <label style={labelStyle}>Confidence Level</label>
                                        <div style={adornmentWrapStyle}>
                                            <input style={adornedInputStyle} type="number" step="1" min="50" max="99"
                                                value={confidenceLevel ?? ''}
                                                placeholder="Uses risk tolerance"
                                                onChange={e => setConfidenceLevel(e.target.value ? Number(e.target.value) : null)} />
                                            <span style={adornmentSuffixStyle}>%</span>
                                        </div>
                                        <div style={{ fontSize: '0.75rem', color: '#888', marginTop: '0.25rem' }}>
                                            Override for risk tolerance
                                        </div>
                                    </div>
                                </div>
                            )}
                        </div>
                    </div>

                    <div style={{ ...cardStyle, marginBottom: '1.5rem' }}>
                        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '1rem' }}>
                            <div>
                                <h3 style={{ margin: 0 }}>Spending Phases</h3>
                                <div style={{ fontSize: '0.8rem', color: '#666', marginTop: '0.25rem' }}>
                                    Set your desired annual spending for each life stage. The optimizer will find the best achievable plan within your portfolio's capacity.
                                </div>
                            </div>
                            <button onClick={addPhase}
                                style={{ padding: '0.25rem 0.75rem', background: '#7b1fa2', color: '#fff', border: 'none', borderRadius: '4px', cursor: 'pointer', fontSize: '0.85rem' }}>
                                + Add Phase
                            </button>
                        </div>
                        {phases.map((phase, i) => (
                            <div key={i}
                                draggable
                                onDragStart={() => handleDragStart(i)}
                                onDragOver={e => handleDragOver(e, i)}
                                onDrop={e => handleDrop(e, i)}
                                onDragEnd={handleDragEnd}
                                style={{
                                    display: 'flex', alignItems: 'center', gap: '0.75rem',
                                    padding: '0.75rem', background: dragOverIndex === i ? '#e3f2fd' : '#f9f9f9',
                                    borderRadius: '4px', marginBottom: '0.5rem',
                                    border: dragOverIndex === i ? '2px dashed #1976d2' : '1px solid #eee',
                                    cursor: 'grab', transition: 'background 0.15s, border 0.15s',
                                }}>
                                <span style={{ cursor: 'grab', color: '#999', fontSize: '1.1rem', userSelect: 'none', padding: '0 0.15rem' }}
                                    title="Drag to reorder">&#x2630;</span>
                                <input style={phaseNameInputStyle} type="text" value={phase.name}
                                    onChange={e => updatePhase(i, 'name', e.target.value)}
                                    placeholder="Phase name" />
                                <span style={smallLabelStyle}>Start</span>
                                <input style={smallInputStyle} type="number" value={phase.start_age}
                                    onChange={e => updatePhase(i, 'start_age', Number(e.target.value))} />
                                <span style={smallLabelStyle}>End</span>
                                <input style={smallInputStyle} type="number" value={phase.end_age ?? ''}
                                    onChange={e => updatePhase(i, 'end_age', e.target.value ? Number(e.target.value) : null)}
                                    placeholder="--" />
                                <span style={smallLabelStyle}>$ Target</span>
                                <div style={smallAdornmentWrapStyle}>
                                    <span style={smallAdornmentStyle}>$</span>
                                    <input style={smallAdornedInputStyle} type="text" inputMode="numeric"
                                        value={phase.target_spending != null ? phase.target_spending.toLocaleString('en-US') : ''}
                                        placeholder="Annual"
                                        onChange={e => { const v = Number(e.target.value.replace(/[^0-9]/g, '')); updatePhase(i, 'target_spending', isNaN(v) ? null : v); }} />
                                </div>
                                <button onClick={() => removePhase(i)}
                                    style={{ padding: '0.25rem 0.5rem', background: '#ef5350', color: '#fff', border: 'none', borderRadius: '4px', cursor: 'pointer', fontSize: '0.8rem' }}>
                                    Remove
                                </button>
                            </div>
                        ))}
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
                                    <div>
                                        <label style={labelStyle}>Conversion Bracket</label>
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
                                        <div style={{ fontSize: '0.75rem', color: '#888', marginTop: '0.25rem' }}>
                                            Maximum tax bracket to fill with conversions each year
                                        </div>
                                    </div>
                                    <div>
                                        <label style={labelStyle}>RMD Target Bracket</label>
                                        <select style={selectStyle} value={rmdTargetBracketRate}
                                            onChange={e => setRmdTargetBracketRate(Number(e.target.value))}>
                                            {[0.10, 0.12, 0.22, 0.24, 0.32, 0.35, 0.37]
                                                .filter(r => r <= conversionBracketRate)
                                                .map(r => (
                                                    <option key={r} value={r}>{(r * 100).toFixed(0)}%</option>
                                                ))}
                                        </select>
                                        <div style={{ fontSize: '0.75rem', color: '#888', marginTop: '0.25rem' }}>
                                            Target bracket for RMDs after conversions are complete
                                        </div>
                                    </div>
                                    <div>
                                        <label style={labelStyle}>RMD Bracket Headroom</label>
                                        <div style={adornmentWrapStyle}>
                                            <input style={adornedInputStyle} type="number" step="1" min="5" max="25"
                                                value={rmdBracketHeadroom}
                                                onChange={e => setRmdBracketHeadroom(Number(e.target.value))} />
                                            <span style={adornmentSuffixStyle}>%</span>
                                        </div>
                                        <div style={{ fontSize: '0.75rem', color: '#888', marginTop: '0.25rem' }}>
                                            Reserve headroom for market growth years. Higher = more conservative.
                                        </div>
                                    </div>
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

            {state === 'results' && result && (() => {
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
                            <button onClick={handleReoptimize}
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
                        <PortfolioFanChart yearlySpending={result.yearly_spending} />
                    </div>

                    <div style={{ ...cardStyle, marginBottom: '1.5rem' }}>
                        <h3 style={{ marginBottom: '1rem' }}>Spending Corridor</h3>
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
            })()}
        </div>
    );
}
