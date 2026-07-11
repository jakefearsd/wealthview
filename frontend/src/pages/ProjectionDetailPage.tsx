import { useState, useEffect, useRef, useMemo } from 'react';
import { useParams, useSearchParams, Link, useNavigate } from 'react-router';
import { getScenario, runProjection, updateScenario } from '../api/projections';
import { useApiQuery } from '../hooks/useApiQuery';
import { useApiMutation } from '../hooks/useApiMutation';
import { formatCurrency } from '../utils/format';
import { cardStyle, tableStyle, thStyle, tdStyle, trHoverStyle } from '../utils/styles';
import {
    findPeakBalance,
    findDepletionYear,
    computeTaxShieldSummary,
    computeTaxMetrics,
    computeTotalSpending,
    computePlanOutcome,
} from '../utils/projectionCalcs';
import { buildProjectionCsv } from '../utils/projectionCsv';
import SummaryCard from '../components/SummaryCard';
import ProjectionChart from '../components/ProjectionChart';
import MilestoneStrip from '../components/MilestoneStrip';
import ScenarioForm from '../components/ScenarioForm';
import IncomeStreamsChart from '../components/IncomeStreamsChart';
import DataTableTab from '../components/DataTableTab';
import IncomeTaxTab from '../components/IncomeTaxTab';
import TaxShieldTab from '../components/TaxShieldTab';
import LoadingState from '../components/LoadingState';
import EmptyState from '../components/EmptyState';
import { useProjectionCache } from '../context/ProjectionCacheContext';
import type { ProjectionResult, CreateScenarioRequest } from '../types/projection';
import { downloadBlob } from '../api/export';
import Button from '../components/Button';
import TabBar from '../components/TabBar';

type TabId = 'chart' | 'flows' | 'table' | 'spending' | 'income_tax' | 'income_streams' | 'tax_shield';

export default function ProjectionDetailPage() {
    const { id } = useParams<{ id: string }>();
    const navigate = useNavigate();
    const [searchParams, setSearchParams] = useSearchParams();
    const cache = useProjectionCache();
    const { data: scenario, loading, refetch } = useApiQuery(() => getScenario(id!));
    const [result, setResult] = useState<ProjectionResult | null>(() => cache.get(id!));
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
        // Runs once after the scenario loads; autoRanRef guards against re-execution,
        // so handleRun/searchParams/setSearchParams are intentionally not dependencies.
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [scenario]);

    const runMutation = useApiMutation<void, ProjectionResult>(
        () => runProjection(id!),
        {
            onSuccess: (data) => {
                setResult(data);
                cache.set(id!, data);
            },
        },
    );
    const running = runMutation.loading;

    function handleRun() {
        void runMutation.mutate();
    }

    const updateMutation = useApiMutation(
        (data: CreateScenarioRequest) => updateScenario(id!, data),
        {
            successMessage: 'Scenario updated',
            onSuccess: () => {
                setEditing(false);
                refetch();
            },
        },
    );

    async function handleUpdate(data: CreateScenarioRequest) {
        const updated = await updateMutation.mutate(data);
        if (updated !== null) {
            await runMutation.mutate();
        }
    }

    const taxShieldSummary = useMemo(
        () => (result?.yearly_data ? computeTaxShieldSummary(result.yearly_data) : null),
        [result],
    );

    const taxMetrics = useMemo(
        () => (result?.yearly_data ? computeTaxMetrics(result.yearly_data) : null),
        [result],
    );

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
                        variant={editing ? 'neutral' : 'warning'}
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
                                            <td style={{ ...tdStyle, textAlign: 'right' }}>{a.expected_return != null ? `${(a.expected_return * 100).toFixed(1)}%` : 'Derived'}</td>
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
                            const outcome = computePlanOutcome(feasibility, depletion);
                            return (
                                <SummaryCard
                                    label={outcome.label}
                                    value={outcome.value}
                                    valueColor={outcome.color}
                                    description={outcome.description}
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
                        <TabBar
                            tabs={[
                                { key: 'chart' as TabId, label: 'Balance Over Time' },
                                { key: 'flows' as TabId, label: 'Annual Flows' },
                                { key: 'table' as TabId, label: 'Data Table' },
                                ...(hasSpendingData ? [{ key: 'spending' as TabId, label: 'Spending Analysis' }] : []),
                                ...(hasIncomeSourceData ? [{ key: 'income_tax' as TabId, label: 'Income & Tax' }] : []),
                                ...(scenario.income_sources.length > 0
                                    ? [{ key: 'income_streams' as TabId, label: 'Income Streams' }] : []),
                                ...(taxShieldSummary && taxShieldSummary.totalDepreciation > 0
                                    ? [{ key: 'tax_shield' as TabId, label: 'Tax Shield' }] : []),
                            ]}
                            active={activeTab}
                            onSelect={setActiveTab}
                            style={{ marginBottom: '1rem' }}
                        />

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
                            <TaxShieldTab summary={taxShieldSummary} />
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
