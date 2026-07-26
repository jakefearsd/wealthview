import { useState, useEffect } from 'react';
import { useParams, useNavigate, Link } from 'react-router';
import toast from 'react-hot-toast';
import { getScenario, optimizeSpending, getGuardrailProfile, reoptimize } from '../api/projections';
import type { Scenario, GuardrailPhase, GuardrailProfileResponse, GuardrailOptimizationRequest, GuardrailYearlySpending } from '../types/projection';
import { useApiMutation } from '../hooks/useApiMutation';
import { cardStyle, inputStyle } from '../utils/styles';
import { formatWholeCurrency } from '../utils/format';
import { defaultOptimizerConfig, fromProfile, toRequest, type OptimizerConfig, type RiskTolerance } from '../utils/optimizerConfig';
import LoadingState from '../components/LoadingState';
import CurrencyInput from '../components/CurrencyInput';
import FormField from '../components/FormField';
import PhaseEditor from '../components/PhaseEditor';
import OptimizerResultsView from '../components/OptimizerResultsView';
import Button from '../components/Button';
import SegmentedControl, { type SegmentedControlOption } from '../components/SegmentedControl';

type OptimizerState = 'configure' | 'running' | 'results';

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

// eslint-disable-next-line react-refresh/only-export-components -- pure helper shared with OptimizerResultsView and unit-tested
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

const RISK_TOLERANCE_OPTIONS: SegmentedControlOption<RiskTolerance>[] = [
    { value: 'conservative', label: 'conservative' },
    { value: 'moderate', label: 'moderate' },
    { value: 'aggressive', label: 'aggressive' },
];

export default function SpendingOptimizerPage() {
    const { id } = useParams<{ id: string }>();
    const navigate = useNavigate();
    const [scenario, setScenario] = useState<Scenario | null>(null);
    const [state, setState] = useState<OptimizerState>('configure');
    const [result, setResult] = useState<GuardrailProfileResponse | null>(null);

    // Advanced parameters section is collapsed by default; not part of the wire config itself.
    const [showAdvanced, setShowAdvanced] = useState(false);

    // Every configure-form field that round-trips to/from the optimization API. See
    // optimizerConfig.ts for the display-unit conventions and the fromProfile/toRequest
    // conversions (including the T24 gate-on-adaptive-rules hydration from `gated_on`).
    const [config, setConfig] = useState<OptimizerConfig>(defaultOptimizerConfig());

    const updateConfig = <K extends keyof OptimizerConfig>(key: K, value: OptimizerConfig[K]) => {
        setConfig(current => ({ ...current, [key]: value }));
    };

    useEffect(() => {
        if (!id) return;
        getScenario(id).then(setScenario).catch(() => toast.error('Failed to load scenario'));
        getGuardrailProfile(id).then(profile => {
            if (profile) {
                setResult(profile);
                setState('results');
                setConfig(fromProfile(profile));
            }
        });
    }, [id]);

    const optimize = useApiMutation(
        (input: GuardrailOptimizationRequest) => optimizeSpending(id!, input),
        {
            successMessage: 'Optimization complete',
            errorMessage: 'Optimization failed',
            onSuccess: (profile) => {
                setResult(profile);
                setState('results');
            },
            onError: () => setState('configure'),
        },
    );

    const reoptimizeMutation = useApiMutation(
        () => reoptimize(id!),
        {
            successMessage: 'Re-optimization complete',
            errorMessage: 'Re-optimization failed',
            onSuccess: (profile) => {
                setResult(profile);
                setState('results');
            },
            onError: () => setState('results'),
        },
    );

    const handleOptimize = async () => {
        if (!id) return;
        setState('running');
        await optimize.mutate(toRequest(config, id));
    };

    const handleReoptimize = async () => {
        if (!id) return;
        setState('running');
        await reoptimizeMutation.mutate(undefined);
    };

    if (!scenario) {
        return <LoadingState message="Loading scenario..." />;
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
                        <Button onClick={handleOptimize} style={{ background: '#7c3aed' }}>
                            Run Optimization
                        </Button>
                    )}
                    {state === 'results' && (
                        <>
                            <Button variant="warning" onClick={() => setState('configure')}>
                                Adjust &amp; Re-run
                            </Button>
                            <Button onClick={() => navigate(`/projections/${id}`)}>
                                Back to Scenario
                            </Button>
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
                {scenario.accounts.length > 0 && (() => {
                    const grouped = scenario.accounts.reduce<Record<string, typeof scenario.accounts>>((acc, a) => {
                        const type = a.account_type.charAt(0).toUpperCase() + a.account_type.slice(1);
                        (acc[type] ??= []).push(a);
                        return acc;
                    }, {});
                    return (
                        <div style={{ color: '#555', marginBottom: '0.35rem' }}>
                            {Object.entries(grouped).map(([type, accounts]) => (
                                <div key={type} style={{ marginBottom: '0.2rem' }}>
                                    <strong style={{ color: '#666' }}>{type}:</strong>{' '}
                                    {accounts.map((a, i) => (
                                        <span key={a.id}>
                                            {a.name} ({formatWholeCurrency(a.initial_balance)})
                                            {i < accounts.length - 1 ? ', ' : ''}
                                        </span>
                                    ))}
                                </div>
                            ))}
                        </div>
                    );
                })()}
                {scenario.income_sources.length > 0 && (
                    <div style={{ color: '#555', marginBottom: '0.35rem' }}>
                        <strong style={{ color: '#666' }}>Income:</strong>{' '}
                        {scenario.income_sources.slice(0, 3).map((src, i, arr) => (
                            <span key={src.income_source_id}>
                                {src.name} ({formatWholeCurrency(src.effective_amount)})
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
                                <input style={inputStyle} type="text" value={config.name}
                                    onChange={e => updateConfig('name', e.target.value)} />
                            </FormField>
                            <FormField label="Essential Spending Floor (per year)">
                                <div style={adornmentWrapStyle}>
                                    <span style={adornmentStyle}>$</span>
                                    <CurrencyInput
                                        style={adornedInputStyle}
                                        value={config.essentialFloor || ''}
                                        onChange={v => updateConfig('essentialFloor', v === '' ? 0 : Number(v))}
                                    />
                                </div>
                            </FormField>
                            <FormField label="Terminal Balance Target">
                                <div style={adornmentWrapStyle}>
                                    <span style={adornmentStyle}>$</span>
                                    <CurrencyInput
                                        style={adornedInputStyle}
                                        value={config.terminalTarget || ''}
                                        onChange={v => updateConfig('terminalTarget', v === '' ? 0 : Number(v))}
                                    />
                                </div>
                            </FormField>
                            <FormField label="Portfolio Safety Net" helpText="Minimum portfolio balance to maintain during retirement">
                                <div style={adornmentWrapStyle}>
                                    <span style={adornmentStyle}>$</span>
                                    <CurrencyInput
                                        style={adornedInputStyle}
                                        value={config.portfolioFloor || ''}
                                        onChange={v => updateConfig('portfolioFloor', v === '' ? 0 : Number(v))}
                                    />
                                </div>
                            </FormField>
                            <FormField label="Risk Tolerance" helpText={
                                config.riskTolerance === 'conservative' ? '95% confidence \u2014 Very likely sustainable without adjustments'
                                : config.riskTolerance === 'moderate' ? '90% confidence \u2014 Sustainable with occasional adjustments in bad markets'
                                : '80% confidence \u2014 Expected spending, requires active management in downturns'
                            }>
                                <SegmentedControl
                                    options={RISK_TOLERANCE_OPTIONS}
                                    value={config.riskTolerance}
                                    onChange={level => updateConfig('riskTolerance', level)}
                                    variant="wide"
                                />
                            </FormField>
                            <FormField label="Spending Flexibility" helpText="Maximum annual spending change">
                                <div style={adornmentWrapStyle}>
                                    <input style={adornedInputStyle} type="number" step="1" min="0" max="50"
                                        value={config.spendingFlexibilityPct || ''}
                                        onChange={e => updateConfig('spendingFlexibilityPct', Number(e.target.value))} />
                                    <span style={adornmentSuffixStyle}>%/yr</span>
                                </div>
                            </FormField>
                        </div>

                        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr 1fr', gap: '1rem', marginTop: '1rem' }}>
                            <FormField label="Phase Blending" helpText="Smooth transitions between life phases">
                                <select style={selectStyle} value={config.phaseBlendYears}
                                    onChange={e => updateConfig('phaseBlendYears', Number(e.target.value))}>
                                    <option value={0}>Off</option>
                                    <option value={1}>1 year</option>
                                    <option value={2}>2 years</option>
                                </select>
                            </FormField>
                            <FormField
                                label="Gate on adaptive spending rules"
                                helpText="Recommended spending assumes you follow the profile's spending-cut rule in downturns (certifies the 'With Guardrail Cuts' number). Uncheck for the conservative never-adjust gate."
                            >
                                <input
                                    type="checkbox"
                                    aria-label="Gate on adaptive spending rules"
                                    checked={config.gateOnAdaptiveRules}
                                    onChange={e => updateConfig('gateOnAdaptiveRules', e.target.checked)}
                                />
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
                                <div style={{ display: 'grid', gridTemplateColumns: 'repeat(5, 1fr)', gap: '1rem', marginTop: '0.75rem' }}>
                                    <FormField label="Cash Reserve" helpText="Years of spending held in cash to avoid selling during downturns">
                                        <select style={selectStyle} value={config.cashReserveYears}
                                            onChange={e => updateConfig('cashReserveYears', Number(e.target.value))}>
                                            <option value={0}>0 years</option>
                                            <option value={1}>1 year</option>
                                            <option value={2}>2 years</option>
                                            <option value={3}>3 years</option>
                                        </select>
                                    </FormField>
                                    <FormField label="Cash Rate" helpText="Expected annual return on cash reserves (money market rate)">
                                        <div style={adornmentWrapStyle}>
                                            <input style={adornedInputStyle} type="number" step="0.1" value={config.cashReturnRatePct || ''}
                                                onChange={e => updateConfig('cashReturnRatePct', Number(e.target.value))} />
                                            <span style={adornmentSuffixStyle}>%</span>
                                        </div>
                                    </FormField>
                                    <FormField label="Trial Count">
                                        <select style={selectStyle} value={config.trialCount}
                                            onChange={e => updateConfig('trialCount', Number(e.target.value))}>
                                            <option value={1000}>1,000</option>
                                            <option value={2500}>2,500</option>
                                            <option value={5000}>5,000</option>
                                            <option value={10000}>10,000</option>
                                        </select>
                                    </FormField>
                                    <FormField label="Confidence Level" helpText="Override for risk tolerance">
                                        <div style={adornmentWrapStyle}>
                                            <input style={adornedInputStyle} type="number" step="1" min="50" max="99"
                                                value={config.confidenceLevelPct ?? ''}
                                                placeholder="Uses risk tolerance"
                                                onChange={e => updateConfig('confidenceLevelPct', e.target.value ? Number(e.target.value) : null)} />
                                            <span style={adornmentSuffixStyle}>%</span>
                                        </div>
                                    </FormField>
                                    <FormField label="Dynamic-Sequencing Bracket Rate (%)" helpText="Target tax bracket for dynamic withdrawal sequencing">
                                        <div style={adornmentWrapStyle}>
                                            <input style={adornedInputStyle} type="number" step="1" min="0" max="37"
                                                value={config.dynSeqBracketRatePct ?? ''}
                                                placeholder="Off"
                                                onChange={e => updateConfig('dynSeqBracketRatePct', e.target.value ? Number(e.target.value) : null)} />
                                            <span style={adornmentSuffixStyle}>%</span>
                                        </div>
                                    </FormField>
                                </div>
                            )}
                        </div>
                    </div>

                    <div style={{ ...cardStyle, marginBottom: '1.5rem' }}>
                        <PhaseEditor phases={config.phases} onPhasesChange={phases => updateConfig('phases', phases)} />
                    </div>

                    <div style={{ ...cardStyle, marginBottom: '1.5rem' }}>
                        <label style={{ display: 'flex', alignItems: 'center', gap: '0.5rem', cursor: 'pointer', marginBottom: config.optimizeConversions ? '1rem' : 0 }}>
                            <input type="checkbox" checked={config.optimizeConversions}
                                onChange={e => updateConfig('optimizeConversions', e.target.checked)} />
                            <h3 style={{ margin: 0 }}>Roth Conversion Strategy</h3>
                        </label>
                        {config.optimizeConversions && (
                            <div>
                                <div style={{ fontSize: '0.8rem', color: '#666', marginBottom: '1rem' }}>
                                    Optimize Roth conversions alongside spending to minimize lifetime taxes.
                                    Conversions shift money from Traditional to Roth accounts, paying tax now at a
                                    lower bracket to avoid higher RMD-driven taxes later.
                                </div>
                                <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr 1fr', gap: '1rem' }}>
                                    <FormField label="Conversion Bracket" helpText="Maximum tax bracket to fill with conversions each year">
                                        <select style={selectStyle} value={config.conversionBracketRate}
                                            onChange={e => {
                                                const rate = Number(e.target.value);
                                                setConfig(current => ({
                                                    ...current,
                                                    conversionBracketRate: rate,
                                                    rmdTargetBracketRate: current.rmdTargetBracketRate > rate
                                                        ? rate
                                                        : current.rmdTargetBracketRate,
                                                }));
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
                                        <select style={selectStyle} value={config.rmdTargetBracketRate}
                                            onChange={e => updateConfig('rmdTargetBracketRate', Number(e.target.value))}>
                                            {[0.10, 0.12, 0.22, 0.24, 0.32, 0.35, 0.37]
                                                .filter(r => r <= config.conversionBracketRate)
                                                .map(r => (
                                                    <option key={r} value={r}>{(r * 100).toFixed(0)}%</option>
                                                ))}
                                        </select>
                                    </FormField>
                                    <FormField label="RMD Bracket Headroom" helpText="Reserve headroom for market growth years. Higher = more conservative.">
                                        <div style={adornmentWrapStyle}>
                                            <input style={adornedInputStyle} type="number" step="1" min="5" max="25"
                                                value={config.rmdBracketHeadroomPct || ''}
                                                onChange={e => updateConfig('rmdBracketHeadroomPct', Number(e.target.value))} />
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
                    <div style={{ color: '#666' }}>Running {config.trialCount.toLocaleString()} Monte Carlo trials...</div>
                </div>
            )}

            {state === 'results' && result && (
                <OptimizerResultsView
                    result={result}
                    onReoptimize={handleReoptimize}
                    retirementDate={scenario.retirement_date}
                />
            )}
        </div>
    );
}
