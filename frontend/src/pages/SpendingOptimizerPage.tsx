import { useState, useEffect } from 'react';
import { useParams, useNavigate, Link } from 'react-router';
import toast from 'react-hot-toast';
import { getScenario, optimizeSpending, getGuardrailProfile, reoptimize } from '../api/projections';
import type { Scenario, GuardrailPhase, GuardrailProfileResponse, GuardrailOptimizationRequest } from '../types/projection';
import SpendingCorridorChart from '../components/SpendingCorridorChart';

type OptimizerState = 'configure' | 'running' | 'results';
type RiskTolerance = 'conservative' | 'moderate' | 'aggressive';

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

    // Advanced parameters (collapsed by default)
    const [showAdvanced, setShowAdvanced] = useState(false);
    const [returnMean, setReturnMean] = useState(10);
    const [returnStddev, setReturnStddev] = useState(15);
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
                setReturnMean(profile.return_mean * 100);
                setReturnStddev(profile.return_stddev * 100);
                setTrialCount(profile.trial_count);
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
                return_mean: returnMean / 100,
                return_stddev: returnStddev / 100,
                trial_count: trialCount,
                phases,
                portfolio_floor: portfolioFloor,
                max_annual_adjustment_rate: spendingFlexibility / 100,
                phase_blend_years: phaseBlendYears,
                risk_tolerance: riskTolerance,
                ...(confidenceLevel != null ? { confidence_level: confidenceLevel / 100 } : {}),
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

    const fmt = (n: number) => n.toLocaleString('en-US', { style: 'currency', currency: 'USD', maximumFractionDigits: 0 });
    const pct = (n: number) => `${(n * 100).toFixed(1)}%`;

    if (!scenario) {
        return <div className="p-6">Loading scenario...</div>;
    }

    return (
        <div className="p-6 max-w-6xl mx-auto">
            <div className="mb-4">
                <Link to={`/projections/${id}`} className="text-blue-600 hover:underline text-sm">
                    &larr; Back to {scenario.name}
                </Link>
            </div>

            <h1 className="text-2xl font-bold mb-1">Spending Optimizer</h1>
            <p className="text-gray-500 mb-6">Scenario: {scenario.name}</p>

            {state === 'configure' && (
                <div className="space-y-6">
                    <div className="bg-white border rounded-lg p-6 space-y-4">
                        <h2 className="text-lg font-semibold">Optimization Parameters</h2>

                        <div className="grid grid-cols-2 gap-4">
                            <div>
                                <label className="block text-sm font-medium text-gray-700 mb-1">Profile Name</label>
                                <input type="text" value={name} onChange={e => setName(e.target.value)}
                                    className="w-full border rounded px-3 py-2" />
                            </div>
                            <div>
                                <label className="block text-sm font-medium text-gray-700 mb-1">Essential Spending Floor ($/yr)</label>
                                <input type="number" value={essentialFloor} onChange={e => setEssentialFloor(Number(e.target.value))}
                                    className="w-full border rounded px-3 py-2" />
                            </div>
                            <div>
                                <label className="block text-sm font-medium text-gray-700 mb-1">Terminal Balance Target ($)</label>
                                <input type="number" value={terminalTarget} onChange={e => setTerminalTarget(Number(e.target.value))}
                                    className="w-full border rounded px-3 py-2" />
                            </div>
                            <div>
                                <label className="block text-sm font-medium text-gray-700 mb-1">Portfolio Safety Net ($)</label>
                                <input type="number" value={portfolioFloor} onChange={e => setPortfolioFloor(Number(e.target.value))}
                                    className="w-full border rounded px-3 py-2" />
                                <p className="text-xs text-gray-500 mt-1">Minimum portfolio balance to maintain during retirement</p>
                            </div>
                        </div>

                        <div className="grid grid-cols-3 gap-4 pt-2">
                            <div>
                                <label className="block text-sm font-medium text-gray-700 mb-2">Risk Tolerance</label>
                                <div className="flex rounded-lg border overflow-hidden">
                                    {(['conservative', 'moderate', 'aggressive'] as RiskTolerance[]).map(level => (
                                        <button key={level} type="button"
                                            onClick={() => setRiskTolerance(level)}
                                            className={`flex-1 py-2 text-sm font-medium capitalize transition-colors ${
                                                riskTolerance === level
                                                    ? 'bg-blue-600 text-white'
                                                    : 'bg-white text-gray-700 hover:bg-gray-50'
                                            }`}>
                                            {level}
                                        </button>
                                    ))}
                                </div>
                                <p className="text-xs text-gray-500 mt-1">
                                    {riskTolerance === 'conservative' && '90% confidence — lowest failure risk'}
                                    {riskTolerance === 'moderate' && '80% confidence — balanced approach'}
                                    {riskTolerance === 'aggressive' && '70% confidence — higher spending, more risk'}
                                </p>
                            </div>
                            <div>
                                <label className="block text-sm font-medium text-gray-700 mb-2">Spending Flexibility (%/yr)</label>
                                <input type="number" step="1" min="0" max="50" value={spendingFlexibility}
                                    onChange={e => setSpendingFlexibility(Number(e.target.value))}
                                    className="w-full border rounded px-3 py-2" />
                                <p className="text-xs text-gray-500 mt-1">Maximum annual spending change (e.g., 5%)</p>
                            </div>
                            <div>
                                <label className="block text-sm font-medium text-gray-700 mb-2">Phase Blending</label>
                                <select value={phaseBlendYears} onChange={e => setPhaseBlendYears(Number(e.target.value))}
                                    className="w-full border rounded px-3 py-2">
                                    <option value={0}>Off</option>
                                    <option value={1}>1 year</option>
                                    <option value={2}>2 years</option>
                                </select>
                                <p className="text-xs text-gray-500 mt-1">Smooth transitions between life phases</p>
                            </div>
                        </div>

                        <div className="border-t pt-4 mt-4">
                            <button type="button" onClick={() => setShowAdvanced(!showAdvanced)}
                                className="text-sm text-gray-500 hover:text-gray-700 flex items-center gap-1">
                                <span className={`inline-block transition-transform ${showAdvanced ? 'rotate-90' : ''}`}>&rsaquo;</span>
                                Advanced Settings
                            </button>
                            {showAdvanced && (
                                <div className="grid grid-cols-2 gap-4 mt-3">
                                    <div>
                                        <label className="block text-sm font-medium text-gray-700 mb-1">Expected Return (%)</label>
                                        <input type="number" step="0.1" value={returnMean} onChange={e => setReturnMean(Number(e.target.value))}
                                            className="w-full border rounded px-3 py-2" />
                                    </div>
                                    <div>
                                        <label className="block text-sm font-medium text-gray-700 mb-1">Return Std Dev (%)</label>
                                        <input type="number" step="0.1" value={returnStddev} onChange={e => setReturnStddev(Number(e.target.value))}
                                            className="w-full border rounded px-3 py-2" />
                                    </div>
                                    <div>
                                        <label className="block text-sm font-medium text-gray-700 mb-1">Trial Count</label>
                                        <select value={trialCount} onChange={e => setTrialCount(Number(e.target.value))}
                                            className="w-full border rounded px-3 py-2">
                                            <option value={1000}>1,000</option>
                                            <option value={2500}>2,500</option>
                                            <option value={5000}>5,000</option>
                                            <option value={10000}>10,000</option>
                                        </select>
                                    </div>
                                    <div>
                                        <label className="block text-sm font-medium text-gray-700 mb-1">Confidence Level (%)</label>
                                        <input type="number" step="1" min="50" max="99"
                                            value={confidenceLevel ?? ''}
                                            placeholder="Uses risk tolerance"
                                            onChange={e => setConfidenceLevel(e.target.value ? Number(e.target.value) : null)}
                                            className="w-full border rounded px-3 py-2" />
                                        <p className="text-xs text-gray-500 mt-1">Override for risk tolerance setting</p>
                                    </div>
                                </div>
                            )}
                        </div>
                    </div>

                    <div className="bg-white border rounded-lg p-6">
                        <div className="flex justify-between items-center mb-2">
                            <h2 className="text-lg font-semibold">Spending Phases</h2>
                            <button onClick={addPhase} className="text-sm text-blue-600 hover:underline">+ Add Phase</button>
                        </div>
                        <p className="text-xs text-gray-500 mb-4">Set your desired annual spending for each life stage. The optimizer will find the best achievable plan within your portfolio's capacity.</p>
                        <div className="space-y-3">
                            {phases.map((phase, i) => (
                                <div key={i} className="flex items-center gap-3 p-3 bg-gray-50 rounded">
                                    <input type="text" value={phase.name}
                                        onChange={e => updatePhase(i, 'name', e.target.value)}
                                        className="border rounded px-2 py-1 flex-1" placeholder="Phase name" />
                                    <div className="flex items-center gap-1">
                                        <label className="text-xs text-gray-500">Start</label>
                                        <input type="number" value={phase.start_age}
                                            onChange={e => updatePhase(i, 'start_age', Number(e.target.value))}
                                            className="border rounded px-2 py-1 w-16" />
                                    </div>
                                    <div className="flex items-center gap-1">
                                        <label className="text-xs text-gray-500">End</label>
                                        <input type="number" value={phase.end_age ?? ''}
                                            onChange={e => updatePhase(i, 'end_age', e.target.value ? Number(e.target.value) : null)}
                                            className="border rounded px-2 py-1 w-16" placeholder="--" />
                                    </div>
                                    <div className="flex items-center gap-1">
                                        <label className="text-xs text-gray-500">$ Target</label>
                                        <input type="number" value={phase.target_spending ?? ''}
                                            onChange={e => updatePhase(i, 'target_spending', e.target.value ? Number(e.target.value) : null)}
                                            className="border rounded px-2 py-1 w-28" placeholder="Annual $" />
                                    </div>
                                    <button onClick={() => removePhase(i)} className="text-red-500 hover:text-red-700 text-sm">Remove</button>
                                </div>
                            ))}
                        </div>
                    </div>

                    <button onClick={handleOptimize}
                        className="bg-blue-600 text-white px-6 py-3 rounded-lg font-medium hover:bg-blue-700">
                        Run Optimization
                    </button>
                </div>
            )}

            {state === 'running' && (
                <div className="flex flex-col items-center justify-center py-20">
                    <div className="animate-spin rounded-full h-12 w-12 border-b-2 border-blue-600 mb-4"></div>
                    <p className="text-gray-600">Running {trialCount.toLocaleString()} Monte Carlo trials...</p>
                </div>
            )}

            {state === 'results' && result && (
                <div className="space-y-6">
                    {result.stale && (
                        <div className="bg-yellow-50 border border-yellow-200 rounded-lg p-4 flex items-center justify-between">
                            <span className="text-yellow-800">This profile is stale &mdash; the scenario has changed since optimization.</span>
                            <button onClick={handleReoptimize} className="text-yellow-800 font-medium hover:underline">
                                Re-optimize
                            </button>
                        </div>
                    )}

                    <div className="grid grid-cols-4 gap-4">
                        <div className="bg-white border rounded-lg p-4 text-center">
                            <p className="text-sm text-gray-500">Median Final Balance</p>
                            <p className="text-xl font-bold">{fmt(result.median_final_balance)}</p>
                        </div>
                        <div className="bg-white border rounded-lg p-4 text-center">
                            <p className="text-sm text-gray-500">Failure Rate</p>
                            <p className="text-xl font-bold">{pct(result.failure_rate)}</p>
                        </div>
                        <div className="bg-white border rounded-lg p-4 text-center">
                            <p className="text-sm text-gray-500">10th Percentile Final</p>
                            <p className="text-xl font-bold">{fmt(result.percentile_10_final)}</p>
                        </div>
                        <div className="bg-white border rounded-lg p-4 text-center">
                            <p className="text-sm text-gray-500">90th Percentile Final</p>
                            <p className="text-xl font-bold">{fmt(result.percentile_90_final)}</p>
                        </div>
                    </div>

                    <div className="bg-white border rounded-lg p-6">
                        <h2 className="text-lg font-semibold mb-4">Spending Corridor</h2>
                        <SpendingCorridorChart yearlySpending={result.yearly_spending} phases={result.phases} />
                    </div>

                    <div className="bg-white border rounded-lg p-6 overflow-x-auto">
                        <h2 className="text-lg font-semibold mb-4">Year-by-Year Breakdown</h2>
                        <table className="w-full text-sm">
                            <thead>
                                <tr className="text-left border-b">
                                    <th className="py-2 px-2">Age</th>
                                    <th className="py-2 px-2">Phase</th>
                                    <th className="py-2 px-2 text-right">Recommended</th>
                                    <th className="py-2 px-2 text-right">Floor</th>
                                    <th className="py-2 px-2 text-right">Discretionary</th>
                                    <th className="py-2 px-2 text-right">Income</th>
                                    <th className="py-2 px-2 text-right">Portfolio Draw</th>
                                    <th className="py-2 px-2 text-right">Portfolio Balance</th>
                                    <th className="py-2 px-2 text-right">Corridor</th>
                                </tr>
                            </thead>
                            <tbody>
                                {result.yearly_spending.map(y => (
                                    <tr key={y.year} className="border-b hover:bg-gray-50">
                                        <td className="py-1 px-2">{y.age}</td>
                                        <td className="py-1 px-2 text-gray-600">{y.phase_name}</td>
                                        <td className="py-1 px-2 text-right font-medium">{fmt(y.recommended)}</td>
                                        <td className="py-1 px-2 text-right">{fmt(y.essential_floor)}</td>
                                        <td className="py-1 px-2 text-right">{fmt(y.discretionary)}</td>
                                        <td className="py-1 px-2 text-right">{fmt(y.income_offset)}</td>
                                        <td className="py-1 px-2 text-right">{fmt(y.portfolio_withdrawal)}</td>
                                        <td className="py-1 px-2 text-right text-gray-500">
                                            {y.portfolio_balance_median != null ? fmt(y.portfolio_balance_median) : '--'}
                                        </td>
                                        <td className="py-1 px-2 text-right text-gray-500">
                                            {fmt(y.corridor_low)} &ndash; {fmt(y.corridor_high)}
                                        </td>
                                    </tr>
                                ))}
                            </tbody>
                        </table>
                    </div>

                    <div className="flex gap-3">
                        <button onClick={() => setState('configure')}
                            className="border border-gray-300 px-4 py-2 rounded hover:bg-gray-50">
                            Adjust &amp; Re-run
                        </button>
                        <button onClick={() => navigate(`/projections/${id}`)}
                            className="bg-blue-600 text-white px-4 py-2 rounded hover:bg-blue-700">
                            Back to Scenario
                        </button>
                    </div>
                </div>
            )}
        </div>
    );
}
