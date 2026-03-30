import { useState, useEffect, useRef, useMemo } from 'react';
import { useParams, useSearchParams, Link, useNavigate } from 'react-router';
import { getScenario, runProjection, updateScenario } from '../api/projections';
import { useApiQuery } from '../hooks/useApiQuery';
import { formatCurrency } from '../utils/format';
import { cardStyle, tableStyle, thStyle, tdStyle, trHoverStyle } from '../utils/styles';
import { findPeakBalance, findDepletionYear } from '../utils/projectionCalcs';
import { buildProjectionCsv } from '../utils/projectionCsv';
import SummaryCard from '../components/SummaryCard';
import ProjectionChart from '../components/ProjectionChart';
import MilestoneStrip from '../components/MilestoneStrip';
import ScenarioForm from '../components/ScenarioForm';
import IncomeStreamsChart from '../components/IncomeStreamsChart';
import DataTableTab from '../components/DataTableTab';
import IncomeTaxTab from '../components/IncomeTaxTab';
import LoadingState from '../components/LoadingState';
import EmptyState from '../components/EmptyState';
import toast from 'react-hot-toast';
import { extractErrorMessage } from '../utils/errorMessage';
import { useProjectionCache } from '../context/ProjectionCacheContext';
import type { ProjectionResult, ProjectionYear, CreateScenarioRequest } from '../types/projection';
import { downloadBlob } from '../api/export';
import Button from '../components/Button';

type TabId = 'chart' | 'flows' | 'table' | 'spending' | 'income_tax' | 'income_streams' | 'tax_shield';

const tabButtonStyle = (active: boolean) => ({
    padding: '0.5rem 1rem',
    background: 'none',
    border: 'none',
    borderBottom: `2px solid ${active ? '#1976d2' : 'transparent'}`,
    color: active ? '#1976d2' : '#666',
    fontWeight: active ? 600 as const : 400 as const,
    cursor: 'pointer' as const,
    fontSize: '0.95rem',
});

export default function ProjectionDetailPage() {
    const { id } = useParams<{ id: string }>();
    const navigate = useNavigate();
    const [searchParams, setSearchParams] = useSearchParams();
    const cache = useProjectionCache();
    const { data: scenario, loading, refetch } = useApiQuery(() => getScenario(id!));
    const [result, setResult] = useState<ProjectionResult | null>(() => cache.get(id!));
    const [running, setRunning] = useState(false);
    const [activeTab, setActiveTab] = useState<TabId>('chart');
    const [editing, setEditing] = useState(false);
    const [expandedTaxYears, setExpandedTaxYears] = useState<Set<number>>(new Set());
    const autoRanRef = useRef(false);

    useEffect(() => {
        if (scenario && searchParams.get('run') === 'true' && !autoRanRef.current) {
            autoRanRef.current = true;
            setSearchParams({}, { replace: true });
            handleRun();
        }
    }, [scenario]);

    async function handleRun() {
        setRunning(true);
        try {
            const data = await runProjection(id!);
            setResult(data);
            cache.set(id!, data);
        } catch (err: unknown) {
            toast.error(extractErrorMessage(err));
        } finally {
            setRunning(false);
        }
    }

    async function handleUpdate(data: CreateScenarioRequest) {
        try {
            await updateScenario(id!, data);
            toast.success('Scenario updated');
            setEditing(false);
            refetch();
            setRunning(true);
            try {
                const res = await runProjection(id!);
                setResult(res);
                cache.set(id!, res);
            } finally {
                setRunning(false);
            }
        } catch (err: unknown) {
            toast.error(extractErrorMessage(err));
        }
    }

    const taxShieldSummary = useMemo(() => {
        if (!result?.yearly_data) return null;
        const years = result.yearly_data.filter(y => y.retired);

        let totalDepreciation = 0;
        let totalLossApplied = 0;
        let estimatedTaxSavings = 0;
        let rothConversionSheltered = 0;
        const perProperty: Record<string, { name: string; taxTreatment: string; depreciation: number; lossApplied: number }> = {};

        for (const y of years) {
            const dep = y.depreciation_total || 0;
            const loss = y.rental_loss_applied || 0;
            totalDepreciation += dep;
            totalLossApplied += loss;

            // Estimated tax savings using effective rate (approximate)
            if (loss > 0 && y.tax_liability != null && y.tax_liability > 0) {
                const taxableIncome = (y.income_streams_total || 0) + (y.roth_conversion_amount || 0);
                const effectiveRate = taxableIncome > 0 ? y.tax_liability / taxableIncome : 0;
                estimatedTaxSavings += loss * effectiveRate;
            }

            // Roth conversion sheltered (approximate)
            if (loss > 0 && y.roth_conversion_amount && y.roth_conversion_amount > 0) {
                rothConversionSheltered += Math.min(loss, y.roth_conversion_amount);
            }

            // Per-property aggregation
            if (y.rental_property_details) {
                for (const d of y.rental_property_details) {
                    const key = d.income_source_id;
                    if (!perProperty[key]) {
                        perProperty[key] = { name: d.property_name, taxTreatment: d.tax_treatment, depreciation: 0, lossApplied: 0 };
                    }
                    perProperty[key].depreciation += d.depreciation;
                    perProperty[key].lossApplied += d.loss_applied_to_income;
                }
            }
        }

        const suspendedLossRemaining = years.length > 0
            ? (years[years.length - 1].suspended_loss_carryforward || 0)
            : 0;

        return {
            totalDepreciation, totalLossApplied, estimatedTaxSavings,
            rothConversionSheltered, suspendedLossRemaining,
            perProperty: Object.values(perProperty),
        };
    }, [result]);

    const taxMetrics = useMemo(() => {
        if (!result?.yearly_data) return null;
        const retiredYears = result.yearly_data.filter(y => y.retired && y.tax_liability != null);
        if (retiredYears.length === 0) return null;

        const hasStateTax = retiredYears.some(y => y.state_tax != null);

        let lifetimeTax = 0;
        let totalStateTax = 0;
        let totalSalt = 0;
        let itemizedCount = 0;
        const rates: number[] = [];

        for (const y of retiredYears) {
            lifetimeTax += y.tax_liability ?? 0;
            totalStateTax += y.state_tax ?? 0;
            if (y.used_itemized_deduction) {
                totalSalt += y.salt_deduction ?? 0;
                itemizedCount++;
            }

            const taxableIncome = (y.income_streams_total ?? 0)
                + (y.roth_conversion_amount ?? 0)
                + (y.withdrawal_from_traditional ?? 0);
            if (taxableIncome > 0) {
                rates.push(((y.tax_liability ?? 0) / taxableIncome) * 100);
            }
        }

        const avgRate = rates.length > 0 ? rates.reduce((a, b) => a + b, 0) / rates.length : 0;

        return {
            lifetimeTax,
            avgRate: Math.round(avgRate * 10) / 10,
            totalStateTax,
            totalSalt,
            itemizedCount,
            totalRetiredYears: retiredYears.length,
            hasStateTax,
        };
    }, [result]);

    if (loading) return <LoadingState message="Loading scenario..." />;
    if (!scenario) return <EmptyState title="Scenario not found" message="This scenario may have been deleted." />;

    const retirementYear = scenario.retirement_date ? new Date(scenario.retirement_date).getFullYear() : null;
    const hasPoolData = result?.yearly_data.some(y => y.traditional_balance !== null) ?? false;
    const hasSpendingData = result?.yearly_data.some(y => y.essential_expenses !== null) ?? false;
    const hasIncomeSourceData = result?.yearly_data.some(y =>
        y.rental_income_gross !== null || y.social_security_taxable !== null || y.self_employment_tax !== null
        || y.state_tax !== null
    ) ?? false;
    const hasSurplusReinvested = result?.yearly_data.some(y => y.surplus_reinvested != null && y.surplus_reinvested > 0) ?? false;

    const computeTotalSpending = (y: ProjectionYear): number | null => {
        // For retired years, withdrawals + income reflects actual spending (including
        // guardrail-recommended amounts). Profile-derived essential + discretionary may
        // show the spending PROFILE's target, which can differ from the optimizer output.
        if (y.retired && (y.withdrawals > 0 || (y.income_streams_total != null && y.income_streams_total > 0))) {
            return y.withdrawals + (y.income_streams_total ?? 0);
        }
        if (y.essential_expenses != null) {
            return y.essential_expenses + (y.discretionary_after_cuts ?? y.discretionary_expenses ?? 0);
        }
        return null;
    };

    const toggleTaxYear = (year: number) => {
        setExpandedTaxYears(prev => {
            const next = new Set(prev);
            if (next.has(year)) next.delete(year);
            else next.add(year);
            return next;
        });
    };

    const handleDownloadCsv = () => {
        if (!result) return;
        const date = new Date().toISOString().slice(0, 10);
        const name = scenario.name.replace(/[^a-zA-Z0-9 -]/g, '').replace(/ /g, '-');
        const csv = buildProjectionCsv(result.yearly_data, {
            hasPoolData, hasSpendingData, hasSurplusReinvested, computeTotalSpending,
        });
        downloadBlob(csv, `projection-${name}-${date}.csv`, 'text/csv');
    };
    const parsedParams = scenario.params_json ? JSON.parse(scenario.params_json) : {};
    const strategyLabels: Record<string, string> = {
        fixed_percentage: 'Fixed Percentage',
        dynamic_percentage: 'Dynamic Percentage',
        vanguard_dynamic_spending: 'Vanguard Dynamic Spending',
    };
    const strategyLabel = strategyLabels[parsedParams.withdrawal_strategy] || 'Fixed Percentage';
    const peak = result ? findPeakBalance(result.yearly_data) : null;
    const depletion = result ? findDepletionYear(result.yearly_data) : null;
    const feasibility = result?.spending_feasibility ?? null;

    return (
        <div>
            <div style={{ marginBottom: '1.5rem' }}>
                <Link to="/projections" style={{ color: '#1976d2', textDecoration: 'none' }}>Projections</Link> / {scenario.name}
            </div>

            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '1.5rem' }}>
                <h2>{scenario.name}</h2>
                <div style={{ display: 'flex', gap: '0.5rem' }}>
                    <Button
                        onClick={() => setEditing(!editing)}
                        style={{ background: editing ? '#757575' : '#ff9800' }}
                    >
                        {editing ? 'Cancel Edit' : 'Edit'}
                    </Button>
                    {!editing && (
                        <>
                            <Button
                                onClick={() => navigate(`/projections/${id}/optimize`)}
                                style={{ background: '#7c3aed' }}
                            >
                                Optimize Spending
                            </Button>
                            <Button
                                onClick={handleRun}
                                disabled={running}
                            >
                                {running ? 'Running...' : 'Run Projection'}
                            </Button>
                        </>
                    )}
                </div>
            </div>

            {editing ? (
                <div style={{ ...cardStyle, marginBottom: '1.5rem' }}>
                    <h3 style={{ marginBottom: '1rem' }}>Edit Scenario</h3>
                    <ScenarioForm initialValues={scenario} onSubmit={handleUpdate} submitLabel="Save & Re-run" />
                </div>
            ) : (
                <>
                    <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr 1fr 1fr', gap: '1rem', marginBottom: '1.5rem' }}>
                        <SummaryCard label="Retirement Date" value={scenario.retirement_date} />
                        <SummaryCard label="End Age" value={String(scenario.end_age)} />
                        <SummaryCard label="Inflation Rate" value={`${(scenario.inflation_rate * 100).toFixed(1)}%`} />
                        <SummaryCard label="Strategy" value={strategyLabel} />
                        <SummaryCard label="Accounts" value={String(scenario.accounts.length)} />
                        {scenario.spending_profile && (
                            <SummaryCard label="Spending Plan" value={scenario.spending_profile.name} />
                        )}
                        {scenario.guardrail_profile?.active && !scenario.spending_profile && (
                            <SummaryCard label="Spending Plan" value={`${scenario.guardrail_profile.name}${scenario.guardrail_profile.stale ? ' (stale)' : ''}`} />
                        )}
                    </div>

                    {scenario.income_sources && scenario.income_sources.length > 0 && (
                        <div style={{ ...cardStyle, marginBottom: '1.5rem' }}>
                            <h3 style={{ marginBottom: '1rem' }}>Income Sources</h3>
                            <table style={tableStyle}>
                                <thead>
                                    <tr>
                                        <th style={thStyle}>Name</th>
                                        <th style={thStyle}>Type</th>
                                        <th style={{ ...thStyle, textAlign: 'right' }}>Start Age</th>
                                        <th style={{ ...thStyle, textAlign: 'right' }}>End Age</th>
                                        <th style={{ ...thStyle, textAlign: 'right' }}>Base Amount</th>
                                        <th style={{ ...thStyle, textAlign: 'right' }}>Override</th>
                                        <th style={{ ...thStyle, textAlign: 'right' }}>Effective</th>
                                    </tr>
                                </thead>
                                <tbody>
                                    {scenario.income_sources.map(is => (
                                        <tr key={is.income_source_id} style={trHoverStyle}>
                                            <td style={tdStyle}>{is.name}</td>
                                            <td style={{ ...tdStyle, textTransform: 'capitalize' }}>{is.income_type.replace(/_/g, ' ')}</td>
                                            <td style={{ ...tdStyle, textAlign: 'right' }}>{is.start_age}</td>
                                            <td style={{ ...tdStyle, textAlign: 'right' }}>{is.end_age != null ? is.end_age : '∞'}</td>
                                            <td style={{ ...tdStyle, textAlign: 'right' }}>
                                                {formatCurrency(is.annual_amount)}
                                                {is.income_type === 'rental_property' && (
                                                    <span style={{ fontSize: '0.75rem', color: '#999', marginLeft: '0.25rem' }}>(gross)</span>
                                                )}
                                            </td>
                                            <td style={{ ...tdStyle, textAlign: 'right', color: '#666' }}>
                                                {is.override_annual_amount != null ? formatCurrency(is.override_annual_amount) : '—'}
                                            </td>
                                            <td style={{ ...tdStyle, textAlign: 'right', fontWeight: 600 }}>
                                                {is.income_type === 'rental_property' && is.annual_net_cash_flow != null
                                                    ? <>{formatCurrency(is.annual_net_cash_flow)}<span style={{ fontSize: '0.75rem', color: '#999', fontWeight: 400, marginLeft: '0.25rem' }}>(net)</span></>
                                                    : formatCurrency(is.effective_amount)
                                                }
                                            </td>
                                        </tr>
                                    ))}
                                </tbody>
                            </table>
                        </div>
                    )}

                    {scenario.accounts.length > 0 && (
                        <div style={{ ...cardStyle, marginBottom: '1.5rem' }}>
                            <h3 style={{ marginBottom: '1rem' }}>Accounts</h3>
                            <table style={tableStyle}>
                                <thead>
                                    <tr>
                                        <th style={thStyle}>Type</th>
                                        <th style={{ ...thStyle, textAlign: 'right' }}>Initial Balance</th>
                                        <th style={{ ...thStyle, textAlign: 'right' }}>Annual Contribution</th>
                                        <th style={{ ...thStyle, textAlign: 'right' }}>Expected Return</th>
                                    </tr>
                                </thead>
                                <tbody>
                                    {scenario.accounts.map(a => (
                                        <tr key={a.id} style={trHoverStyle}>
                                            <td style={{ ...tdStyle, textTransform: 'capitalize' }}>{a.account_type || 'taxable'}</td>
                                            <td style={{ ...tdStyle, textAlign: 'right' }}>{formatCurrency(a.initial_balance)}</td>
                                            <td style={{ ...tdStyle, textAlign: 'right' }}>{formatCurrency(a.annual_contribution)}</td>
                                            <td style={{ ...tdStyle, textAlign: 'right' }}>{(a.expected_return * 100).toFixed(1)}%</td>
                                        </tr>
                                    ))}
                                </tbody>
                            </table>
                        </div>
                    )}
                </>
            )}

            {result && (
                <>
                    {feasibility && !feasibility.spending_feasible && (
                        <div style={{
                            background: '#fff3e0', borderLeft: '4px solid #e65100', padding: '1rem',
                            marginBottom: '1rem', borderRadius: '4px',
                        }}>
                            <strong>Spending Shortfall Detected</strong>
                            <div style={{ marginTop: '0.5rem', fontSize: '0.9rem', color: '#333' }}>
                                Your spending plan requires {formatCurrency(feasibility.required_annual_spending)}/yr
                                but your portfolio can sustain approximately {formatCurrency(feasibility.sustainable_annual_spending)}/yr.
                                {feasibility.first_shortfall_age != null && (
                                    <> Shortfall begins at age {feasibility.first_shortfall_age}.</>
                                )}
                                {' '}Review the Spending Analysis tab for year-by-year details.
                            </div>
                        </div>
                    )}

                    <div style={{ display: 'grid', gridTemplateColumns: 'repeat(4, 1fr)', gap: '1rem', marginBottom: '1rem' }}>
                        <SummaryCard
                            label="Final Balance"
                            value={formatCurrency(result.final_balance)}
                            valueColor={result.final_balance > 0 ? '#2e7d32' : '#d32f2f'}
                            description="Portfolio value at the end of your projection period."
                        />
                        <SummaryCard
                            label="Years in Retirement"
                            value={String(result.years_in_retirement)}
                            description="Years between your retirement date and projection end."
                        />
                        <SummaryCard
                            label="Peak Balance"
                            value={formatCurrency(peak!.balance)}
                            subtext={`(year ${peak!.year})`}
                            description="Highest portfolio value reached during the projection."
                        />
                        {(() => {
                            const hasProfile = feasibility !== null;
                            const depletes = depletion !== null;
                            let outcomeLabel: string, outcomeValue: string, outcomeColor: string, outcomeDesc: string;

                            if (!hasProfile) {
                                outcomeLabel = "Depletion Year";
                                outcomeValue = depletes ? `${depletion.year} (age ${depletion.age})` : "Never";
                                outcomeColor = depletes ? '#d32f2f' : '#2e7d32';
                                outcomeDesc = depletes
                                    ? "The year your portfolio reaches $0."
                                    : "Your money outlasts the plan.";
                            } else if (depletes) {
                                outcomeLabel = "Plan Outcome";
                                outcomeValue = `Depleted at age ${depletion.age}`;
                                outcomeColor = '#d32f2f';
                                outcomeDesc = `Portfolio reaches $0 in ${depletion.year}.`;
                            } else if (feasibility.spending_feasible) {
                                outcomeLabel = "Plan Outcome";
                                outcomeValue = "Fully Sustainable";
                                outcomeColor = '#2e7d32';
                                outcomeDesc = `Tightest year: ${formatCurrency(feasibility.sustainable_annual_spending)}/yr available vs ${formatCurrency(feasibility.required_annual_spending)}/yr needed`;
                            } else {
                                outcomeLabel = "Plan Outcome";
                                outcomeValue = `Underfunded at age ${feasibility.first_shortfall_age}`;
                                outcomeColor = '#d32f2f';
                                outcomeDesc = `Sustains ${formatCurrency(feasibility.sustainable_annual_spending)}/yr of ${formatCurrency(feasibility.required_annual_spending)}/yr needed`;
                            }

                            return (
                                <SummaryCard
                                    label={outcomeLabel}
                                    value={outcomeValue}
                                    valueColor={outcomeColor}
                                    description={outcomeDesc}
                                />
                            );
                        })()}
                    </div>

                    {taxMetrics && (
                        <div style={{
                            display: 'grid',
                            gridTemplateColumns: taxMetrics.hasStateTax ? 'repeat(4, 1fr)' : 'repeat(2, 1fr)',
                            gap: '1rem',
                            marginBottom: '1rem',
                        }}>
                            <SummaryCard
                                label="Lifetime Tax"
                                value={formatCurrency(taxMetrics.lifetimeTax)}
                                valueColor="#d32f2f"
                                subtext="Federal + state taxes over retirement"
                            />
                            <SummaryCard
                                label="Avg Effective Rate"
                                value={`${taxMetrics.avgRate}%`}
                                subtext="Average tax rate on retirement income"
                            />
                            {taxMetrics.hasStateTax && (
                                <SummaryCard
                                    label="Total State Tax"
                                    value={formatCurrency(taxMetrics.totalStateTax)}
                                    valueColor="#e65100"
                                    subtext="Cumulative state tax over retirement"
                                />
                            )}
                            {taxMetrics.hasStateTax && (
                                <SummaryCard
                                    label="SALT Claimed"
                                    value={formatCurrency(taxMetrics.totalSalt)}
                                    valueColor="#2e7d32"
                                    subtext={`${taxMetrics.itemizedCount} of ${taxMetrics.totalRetiredYears} years itemized`}
                                />
                            )}
                        </div>
                    )}

                    <div style={{ marginBottom: '1.5rem' }}>
                        <MilestoneStrip result={result} retirementYear={retirementYear} />
                    </div>

                    <div style={cardStyle}>
                        <div style={{ borderBottom: '1px solid #e0e0e0', marginBottom: '1rem', display: 'flex', gap: '0.25rem' }}>
                            <button style={tabButtonStyle(activeTab === 'chart')} onClick={() => setActiveTab('chart')}>
                                Balance Over Time
                            </button>
                            <button style={tabButtonStyle(activeTab === 'flows')} onClick={() => setActiveTab('flows')}>
                                Annual Flows
                            </button>
                            <button style={tabButtonStyle(activeTab === 'table')} onClick={() => setActiveTab('table')}>
                                Data Table
                            </button>
                            {hasSpendingData && (
                                <button style={tabButtonStyle(activeTab === 'spending')} onClick={() => setActiveTab('spending')}>
                                    Spending Analysis
                                </button>
                            )}
                            {hasIncomeSourceData && (
                                <button style={tabButtonStyle(activeTab === 'income_tax')} onClick={() => setActiveTab('income_tax')}>
                                    Income & Tax
                                </button>
                            )}
                            {scenario.income_sources.length > 0 && (
                                <button style={tabButtonStyle(activeTab === 'income_streams')} onClick={() => setActiveTab('income_streams')}>
                                    Income Streams
                                </button>
                            )}
                            {taxShieldSummary && taxShieldSummary.totalDepreciation > 0 && (
                                <button style={tabButtonStyle(activeTab === 'tax_shield')} onClick={() => setActiveTab('tax_shield')}>
                                    Tax Shield
                                </button>
                            )}
                        </div>

                        {activeTab === 'chart' && (
                            <ProjectionChart data={result.yearly_data} retirementYear={retirementYear} mode="balance" />
                        )}

                        {activeTab === 'flows' && (
                            <ProjectionChart data={result.yearly_data} retirementYear={retirementYear} mode="flows" />
                        )}

                        {activeTab === 'spending' && hasSpendingData && (
                            <ProjectionChart data={result.yearly_data} retirementYear={retirementYear} mode="spending" />
                        )}

                        {activeTab === 'income_tax' && hasIncomeSourceData && (
                            <IncomeTaxTab
                                yearlyData={result.yearly_data}
                                retirementYear={retirementYear}
                                expandedTaxYears={expandedTaxYears}
                                onToggleTaxYear={toggleTaxYear}
                            />
                        )}

                        {activeTab === 'income_streams' && scenario.income_sources.length > 0 && (
                            <IncomeStreamsChart
                                data={result.yearly_data}
                                incomeSources={scenario.income_sources}
                                retirementYear={retirementYear}
                            />
                        )}

                        {activeTab === 'tax_shield' && taxShieldSummary && (
                            <div style={{ padding: '1rem' }}>
                                <h3 style={{ marginBottom: '0.25rem' }}>Depreciation Tax Shield Summary</h3>
                                <p style={{ fontSize: '0.85rem', color: '#888', marginBottom: '1rem' }}>
                                    Values marked (approx.) are estimates based on effective tax rates.
                                </p>
                                <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)', gap: '1rem' }}>
                                    <div style={{ padding: '1rem', background: '#f5f5f5', borderRadius: 8 }}>
                                        <div style={{ color: '#666', fontSize: '0.85rem' }}>Total Depreciation Taken</div>
                                        <div style={{ fontSize: '1.5rem', fontWeight: 600 }}>{formatCurrency(taxShieldSummary.totalDepreciation)}</div>
                                    </div>
                                    <div style={{ padding: '1rem', background: '#f5f5f5', borderRadius: 8 }}>
                                        <div style={{ color: '#666', fontSize: '0.85rem' }}>Total Loss Applied to Income</div>
                                        <div style={{ fontSize: '1.5rem', fontWeight: 600 }}>{formatCurrency(taxShieldSummary.totalLossApplied)}</div>
                                    </div>
                                    <div style={{ padding: '1rem', background: '#e8f5e9', borderRadius: 8 }}>
                                        <div style={{ color: '#666', fontSize: '0.85rem' }}>Estimated Tax Savings (approx.)</div>
                                        <div style={{ fontSize: '1.5rem', fontWeight: 600, color: '#2e7d32' }}>{formatCurrency(taxShieldSummary.estimatedTaxSavings)}</div>
                                    </div>
                                    <div style={{ padding: '1rem', background: '#e3f2fd', borderRadius: 8 }}>
                                        <div style={{ color: '#666', fontSize: '0.85rem' }}>Roth Conversion Sheltered (approx.)</div>
                                        <div style={{ fontSize: '1.5rem', fontWeight: 600, color: '#1565c0' }}>{formatCurrency(taxShieldSummary.rothConversionSheltered)}</div>
                                    </div>
                                    <div style={{ padding: '1rem', background: '#fff3e0', borderRadius: 8 }}>
                                        <div style={{ color: '#666', fontSize: '0.85rem' }}>Suspended Losses Remaining</div>
                                        <div style={{ fontSize: '1.5rem', fontWeight: 600 }}>{formatCurrency(taxShieldSummary.suspendedLossRemaining)}</div>
                                    </div>
                                </div>

                                {taxShieldSummary.perProperty.length > 0 && (
                                    <div style={{ marginTop: '1.5rem' }}>
                                        <h4 style={{ marginBottom: '0.5rem' }}>Per-Property Breakdown</h4>
                                        <table style={tableStyle}>
                                            <thead>
                                                <tr style={{ background: '#fafafa' }}>
                                                    <th style={thStyle}>Property</th>
                                                    <th style={thStyle}>Classification</th>
                                                    <th style={{ ...thStyle, textAlign: 'right' }}>Total Depreciation</th>
                                                    <th style={{ ...thStyle, textAlign: 'right' }}>Total Loss Applied</th>
                                                </tr>
                                            </thead>
                                            <tbody>
                                                {taxShieldSummary.perProperty.map((p, i) => (
                                                    <tr key={i} style={trHoverStyle}>
                                                        <td style={tdStyle}>{p.name}</td>
                                                        <td style={tdStyle}>
                                                            <span style={{
                                                                fontSize: '0.75rem', padding: '2px 6px', borderRadius: 4,
                                                                background: p.taxTreatment === 'rental_passive' ? '#e0e0e0'
                                                                    : p.taxTreatment === 'rental_active_reps' ? '#c8e6c9' : '#bbdefb',
                                                                color: '#333',
                                                            }}>
                                                                {p.taxTreatment === 'rental_passive' ? 'Passive'
                                                                    : p.taxTreatment === 'rental_active_reps' ? 'REPS' : 'STR'}
                                                            </span>
                                                        </td>
                                                        <td style={{ ...tdStyle, textAlign: 'right' }}>{formatCurrency(p.depreciation)}</td>
                                                        <td style={{ ...tdStyle, textAlign: 'right' }}>{formatCurrency(p.lossApplied)}</td>
                                                    </tr>
                                                ))}
                                            </tbody>
                                        </table>
                                    </div>
                                )}
                            </div>
                        )}

                        {activeTab === 'table' && (
                            <DataTableTab
                                yearlyData={result.yearly_data}
                                hasPoolData={hasPoolData}
                                hasSpendingData={hasSpendingData}
                                hasSurplusReinvested={hasSurplusReinvested}
                                computeTotalSpending={computeTotalSpending}
                                onDownloadCsv={handleDownloadCsv}
                            />
                        )}
                    </div>
                </>
            )}
        </div>
    );
}
