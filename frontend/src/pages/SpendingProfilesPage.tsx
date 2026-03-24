import { useState, useCallback, useEffect } from 'react';
import { useNavigate, Link } from 'react-router';
import { listSpendingProfiles, createSpendingProfile, updateSpendingProfile, deleteSpendingProfile } from '../api/spendingProfiles';
import { listScenarios, getGuardrailProfile, deleteGuardrailProfile, reoptimize } from '../api/projections';
import { useApiQuery } from '../hooks/useApiQuery';
import { useCrudForm } from '../hooks/useCrudForm';
import { cardStyle, inputStyle, labelStyle } from '../utils/styles';
import { formatCurrency } from '../utils/format';
import CurrencyInput from '../components/CurrencyInput';
import HelpText from '../components/HelpText';
import toast from 'react-hot-toast';
import type { SpendingProfile, CreateSpendingProfileRequest, SpendingTier, GuardrailProfileResponse } from '../types/projection';

function defaultSpendingTier(): SpendingTier {
    return { name: '', start_age: 55, end_age: null, essential_expenses: 0, discretionary_expenses: 0 };
}

const initialFormData: CreateSpendingProfileRequest = {
    name: '',
    essential_expenses: 40000,
    discretionary_expenses: 20000,
    spending_tiers: [],
};

interface GuardrailWithScenario {
    profile: GuardrailProfileResponse;
    scenarioName: string;
}

export default function SpendingProfilesPage() {
    const navigate = useNavigate();
    const { data: profiles, loading, refetch } = useApiQuery(listSpendingProfiles);
    const [showForm, setShowForm] = useState(false);
    const [guardrails, setGuardrails] = useState<GuardrailWithScenario[]>([]);
    const [guardrailsLoading, setGuardrailsLoading] = useState(true);
    const [reoptimizingId, setReoptimizingId] = useState<string | null>(null);

    const loadGuardrails = useCallback(async () => {
        setGuardrailsLoading(true);
        try {
            const scenarios = await listScenarios();
            const results = await Promise.all(
                scenarios.map(async s => {
                    const profile = await getGuardrailProfile(s.id);
                    return profile ? { profile, scenarioName: s.name } : null;
                })
            );
            setGuardrails(results.filter((r): r is GuardrailWithScenario => r !== null));
        } catch {
            setGuardrails([]);
        } finally {
            setGuardrailsLoading(false);
        }
    }, []);

    useEffect(() => { loadGuardrails(); }, [loadGuardrails]);

    async function handleReoptimize(scenarioId: string) {
        setReoptimizingId(scenarioId);
        try {
            await reoptimize(scenarioId);
            toast.success('Re-optimization complete');
            loadGuardrails();
        } catch {
            toast.error('Re-optimization failed');
        } finally {
            setReoptimizingId(null);
        }
    }

    async function handleDeleteGuardrail(scenarioId: string) {
        if (!confirm('Delete this guardrail profile? The scenario will revert to its spending profile for projections.')) return;
        try {
            await deleteGuardrailProfile(scenarioId);
            toast.success('Guardrail profile deleted');
            loadGuardrails();
        } catch {
            toast.error('Failed to delete guardrail profile');
        }
    }

    const onSuccess = useCallback(() => {
        setShowForm(false);
        refetch();
    }, [refetch]);

    const { editingId, formData, setFormData, isSubmitting: saving, handleSave, handleDelete, resetForm: crudReset, startEdit: crudStartEdit } = useCrudForm<SpendingProfile, CreateSpendingProfileRequest>({
        createFn: createSpendingProfile,
        updateFn: updateSpendingProfile,
        deleteFn: deleteSpendingProfile,
        entityName: 'Profile',
        initialFormData,
        onSuccess,
        validate: (data) => !data.name ? 'Name is required' : undefined,
    });

    const resetForm = useCallback(() => {
        crudReset();
        setShowForm(false);
    }, [crudReset]);

    function startEdit(profile: SpendingProfile) {
        crudStartEdit(profile.id, {
            name: profile.name,
            essential_expenses: profile.essential_expenses,
            discretionary_expenses: profile.discretionary_expenses,
            spending_tiers: profile.spending_tiers?.length > 0 ? [...profile.spending_tiers] : [],
        });
        setShowForm(true);
    }

    function updateTier(index: number, field: keyof SpendingTier, value: string | number | null) {
        setFormData(prev => ({
            ...prev,
            spending_tiers: prev.spending_tiers.map((t, i) => i === index ? { ...t, [field]: value } : t),
        }));
    }

    if (loading) return <div>Loading...</div>;

    const { name, essential_expenses: essentialExpenses, discretionary_expenses: discretionaryExpenses, spending_tiers: spendingTiers } = formData;

    return (
        <div>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '1.5rem' }}>
                <div>
                    <h2>Spending Profiles</h2>
                    <div style={{ fontSize: '0.85rem', color: '#666', marginTop: '0.25rem' }}>
                        Spending profiles define your retirement cost of living. Attach one to a projection scenario to see whether your portfolio can sustain your planned lifestyle.
                    </div>
                </div>
                <button
                    onClick={() => { if (showForm) resetForm(); else setShowForm(true); }}
                    style={{ padding: '0.5rem 1rem', background: '#1976d2', color: '#fff', border: 'none', borderRadius: '4px', cursor: 'pointer' }}
                >
                    {showForm ? 'Cancel' : 'New Profile'}
                </button>
            </div>

            {showForm && (
                <div style={{ ...cardStyle, marginBottom: '1.5rem' }}>
                    <h3 style={{ marginBottom: '1rem' }}>{editingId ? 'Edit Profile' : 'Create Profile'}</h3>
                    <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr 1fr', gap: '1rem', marginBottom: '1rem' }}>
                        <div>
                            <label style={labelStyle}>Name</label>
                            <input style={inputStyle} value={name} onChange={e => setFormData(prev => ({ ...prev, name: e.target.value }))} placeholder="Retirement Spending" />
                        </div>
                        <div>
                            <label style={labelStyle}>Essential Expenses (annual)</label>
                            <CurrencyInput style={inputStyle} value={essentialExpenses || ''} onChange={v => setFormData(prev => ({ ...prev, essential_expenses: Number(v) || 0 }))} />
                            <HelpText>Default non-negotiable annual costs when no spending tier matches the current age.</HelpText>
                        </div>
                        <div>
                            <label style={labelStyle}>Discretionary Expenses (annual)</label>
                            <CurrencyInput style={inputStyle} value={discretionaryExpenses || ''} onChange={v => setFormData(prev => ({ ...prev, discretionary_expenses: Number(v) || 0 }))} />
                            <HelpText>Default flexible annual spending when no spending tier matches the current age.</HelpText>
                        </div>
                    </div>

                    {/* Spending Tiers Section */}
                    <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '0.5rem', marginTop: '1rem' }}>
                        <div>
                            <h4>Spending Tiers</h4>
                            <div style={{ fontSize: '0.8rem', color: '#666', marginTop: '0.15rem' }}>
                                Define age-based spending phases. When tiers are defined, spending varies by life stage instead of staying flat. Amounts are in today's dollars; inflation is applied automatically.
                            </div>
                        </div>
                        <button
                            onClick={() => setFormData(prev => ({ ...prev, spending_tiers: [...prev.spending_tiers, defaultSpendingTier()] }))}
                            style={{ padding: '0.25rem 0.75rem', background: '#7b1fa2', color: '#fff', border: 'none', borderRadius: '4px', cursor: 'pointer', fontSize: '0.85rem' }}
                        >
                            + Add Spending Tier
                        </button>
                    </div>
                    {spendingTiers.map((tier, idx) => (
                        <div key={idx} style={{ display: 'grid', gridTemplateColumns: '1.5fr 1fr 1fr 1fr 1fr auto', gap: '1rem', marginBottom: '0.75rem', alignItems: 'end' }}>
                            <div>
                                <label style={labelStyle}>Phase Name</label>
                                <input style={inputStyle} value={tier.name} onChange={e => updateTier(idx, 'name', e.target.value)} placeholder="e.g., Go-Go Years" />
                            </div>
                            <div>
                                <label style={labelStyle}>Start Age</label>
                                <input style={inputStyle} type="number" value={tier.start_age || ''} onChange={e => updateTier(idx, 'start_age', Number(e.target.value))} />
                            </div>
                            <div>
                                <label style={labelStyle}>End Age (blank = forever)</label>
                                <input style={inputStyle} type="number" value={tier.end_age ?? ''} onChange={e => updateTier(idx, 'end_age', e.target.value ? Number(e.target.value) : null)} />
                            </div>
                            <div>
                                <label style={labelStyle}>Essential (annual)</label>
                                <CurrencyInput style={inputStyle} value={tier.essential_expenses || ''} onChange={v => updateTier(idx, 'essential_expenses', Number(v) || 0)} />
                            </div>
                            <div>
                                <label style={labelStyle}>Discretionary (annual)</label>
                                <CurrencyInput style={inputStyle} value={tier.discretionary_expenses || ''} onChange={v => updateTier(idx, 'discretionary_expenses', Number(v) || 0)} />
                            </div>
                            <div>
                                <button
                                    onClick={() => setFormData(prev => ({ ...prev, spending_tiers: prev.spending_tiers.filter((_, i) => i !== idx) }))}
                                    style={{ padding: '0.5rem', background: 'none', border: '1px solid #d32f2f', color: '#d32f2f', borderRadius: '4px', cursor: 'pointer', fontSize: '0.85rem' }}
                                >
                                    Remove
                                </button>
                            </div>
                        </div>
                    ))}

                    <button
                        onClick={handleSave}
                        disabled={saving}
                        style={{ padding: '0.5rem 1rem', background: '#1976d2', color: '#fff', border: 'none', borderRadius: '4px', cursor: 'pointer', marginTop: '0.5rem' }}
                    >
                        {saving ? 'Saving...' : (editingId ? 'Update Profile' : 'Create Profile')}
                    </button>
                </div>
            )}

            {profiles?.length === 0 ? (
                <div style={{ ...cardStyle, textAlign: 'center', padding: '3rem' }}>
                    <div style={{ color: '#999', fontSize: '1.1rem' }}>No spending profiles yet. Create one to attach to your retirement scenarios.</div>
                </div>
            ) : (
                <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(420px, 1fr))', gap: '1rem' }}>
                    {profiles?.map(p => {
                        const totalBase = p.essential_expenses + p.discretionary_expenses;
                        return (
                        <div key={p.id} style={cardStyle}>
                            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', marginBottom: '1rem' }}>
                                <h3 style={{ margin: 0 }}>{p.name}</h3>
                                <div style={{ display: 'flex', gap: '0.5rem' }}>
                                    <button onClick={() => startEdit(p)} style={{ background: 'none', border: 'none', color: '#1976d2', cursor: 'pointer', fontSize: '0.85rem' }}>Edit</button>
                                    <button onClick={() => handleDelete(p.id)} style={{ background: 'none', border: 'none', color: '#d32f2f', cursor: 'pointer', fontSize: '0.85rem' }}>Delete</button>
                                </div>
                            </div>
                            <div style={{ fontSize: '1.3rem', fontWeight: 700, marginBottom: '1rem', color: '#b71c1c' }}>
                                {formatCurrency(totalBase)}<span style={{ fontSize: '0.8rem', fontWeight: 400, color: '#888' }}> / year</span>
                            </div>
                            <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '0.75rem', fontSize: '0.9rem', marginBottom: '0.75rem' }}>
                                <div>
                                    <div style={{ color: '#999', fontSize: '0.75rem', marginBottom: '0.15rem' }}>Essential</div>
                                    <div style={{ color: '#444', fontWeight: 500 }}>{formatCurrency(p.essential_expenses)}</div>
                                </div>
                                <div>
                                    <div style={{ color: '#999', fontSize: '0.75rem', marginBottom: '0.15rem' }}>Discretionary</div>
                                    <div style={{ color: '#444', fontWeight: 500 }}>{formatCurrency(p.discretionary_expenses)}</div>
                                </div>
                                <div>
                                    <div style={{ color: '#999', fontSize: '0.75rem', marginBottom: '0.15rem' }}>Monthly Equivalent</div>
                                    <div style={{ color: '#444' }}>{formatCurrency(totalBase / 12)}</div>
                                </div>
                                <div>
                                    <div style={{ color: '#999', fontSize: '0.75rem', marginBottom: '0.15rem' }}>Spending Tiers</div>
                                    <div style={{ color: '#444' }}>{p.spending_tiers?.length > 0 ? `${p.spending_tiers.length} phase${p.spending_tiers.length > 1 ? 's' : ''}` : 'None (flat spending)'}</div>
                                </div>
                            </div>
                            {p.spending_tiers?.length > 0 && (
                                <div style={{ borderTop: '1px solid #eee', paddingTop: '0.75rem' }}>
                                    {p.spending_tiers.map((t, idx) => (
                                        <div key={idx} style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', padding: '0.35rem 0', fontSize: '0.85rem' }}>
                                            <span style={{ color: '#555', fontWeight: 500 }}>{t.name || `Phase ${idx + 1}`}</span>
                                            <span style={{ color: '#888' }}>
                                                Ages {t.start_age}-{t.end_age ?? '\u221E'} &middot; {formatCurrency(t.essential_expenses + t.discretionary_expenses)}/yr
                                            </span>
                                        </div>
                                    ))}
                                </div>
                            )}
                        </div>
                        );
                    })}
                </div>
            )}

            {/* Guardrail Spending Profiles Section */}
            {!guardrailsLoading && guardrails.length > 0 && (
                <div style={{ marginTop: '2rem' }}>
                    <h2 style={{ marginBottom: '0.25rem' }}>Monte Carlo Guardrail Profiles</h2>
                    <div style={{ fontSize: '0.85rem', color: '#666', marginBottom: '1rem' }}>
                        Optimized spending plans generated by the Monte Carlo simulator. These override the standard spending profile on their attached scenario.
                    </div>
                    <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(420px, 1fr))', gap: '1rem' }}>
                        {guardrails.map(({ profile: g, scenarioName }) => {
                            const spendingYears = g.yearly_spending.filter(y => y.recommended > 0);
                            const minSpend = spendingYears.length > 0 ? Math.min(...spendingYears.map(y => y.recommended)) : 0;
                            const maxSpend = spendingYears.length > 0 ? Math.max(...spendingYears.map(y => y.recommended)) : 0;
                            return (
                                <div key={g.id} style={{ ...cardStyle, borderLeft: '4px solid #7c3aed' }}>
                                    <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', marginBottom: '1rem' }}>
                                        <div>
                                            <h3 style={{ margin: 0 }}>{g.name || 'Guardrail Profile'}</h3>
                                            <div style={{ fontSize: '0.8rem', marginTop: '0.15rem' }}>
                                                Projection: <Link to={`/projections/${g.scenario_id}`} style={{ color: '#1976d2', textDecoration: 'none' }}>{scenarioName}</Link>
                                            </div>
                                        </div>
                                        <div style={{ display: 'flex', gap: '0.5rem', alignItems: 'center' }}>
                                            {g.stale && (
                                                <span style={{ fontSize: '0.75rem', color: '#e65100', background: '#fff3e0', padding: '0.15rem 0.5rem', borderRadius: '4px' }}>
                                                    Stale
                                                </span>
                                            )}
                                            <button
                                                onClick={() => navigate(`/projections/${g.scenario_id}/optimize`)}
                                                style={{ background: 'none', border: 'none', color: '#1976d2', cursor: 'pointer', fontSize: '0.85rem' }}
                                            >
                                                View
                                            </button>
                                            <button
                                                onClick={() => handleReoptimize(g.scenario_id)}
                                                disabled={reoptimizingId === g.scenario_id}
                                                style={{ background: 'none', border: 'none', color: '#7c3aed', cursor: 'pointer', fontSize: '0.85rem', opacity: reoptimizingId === g.scenario_id ? 0.5 : 1 }}
                                            >
                                                {reoptimizingId === g.scenario_id ? 'Running...' : 'Re-optimize'}
                                            </button>
                                            <button
                                                onClick={() => handleDeleteGuardrail(g.scenario_id)}
                                                style={{ background: 'none', border: 'none', color: '#d32f2f', cursor: 'pointer', fontSize: '0.85rem' }}
                                            >
                                                Delete
                                            </button>
                                        </div>
                                    </div>
                                    <div style={{ fontSize: '1.3rem', fontWeight: 700, marginBottom: '1rem', color: '#7c3aed' }}>
                                        {formatCurrency(minSpend)} &ndash; {formatCurrency(maxSpend)}
                                        <span style={{ fontSize: '0.8rem', fontWeight: 400, color: '#888' }}> / year range</span>
                                    </div>
                                    <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '0.75rem', fontSize: '0.9rem', marginBottom: '0.75rem' }}>
                                        <div>
                                            <div style={{ color: '#999', fontSize: '0.75rem', marginBottom: '0.15rem' }}>Essential Floor</div>
                                            <div style={{ color: '#444', fontWeight: 500 }}>{formatCurrency(g.essential_floor)}</div>
                                        </div>
                                        <div>
                                            <div style={{ color: '#999', fontSize: '0.75rem', marginBottom: '0.15rem' }}>Failure Rate</div>
                                            <div style={{ color: g.failure_rate > 0.1 ? '#d32f2f' : '#2e7d32', fontWeight: 500 }}>
                                                {(g.failure_rate * 100).toFixed(1)}%
                                            </div>
                                        </div>
                                        <div>
                                            <div style={{ color: '#999', fontSize: '0.75rem', marginBottom: '0.15rem' }}>Median Final Balance</div>
                                            <div style={{ color: '#444', fontWeight: 500 }}>{formatCurrency(g.median_final_balance)}</div>
                                        </div>
                                        <div>
                                            <div style={{ color: '#999', fontSize: '0.75rem', marginBottom: '0.15rem' }}>Trials</div>
                                            <div style={{ color: '#444' }}>
                                                {g.trial_count.toLocaleString()} at {(g.confidence_level * 100).toFixed(0)}% confidence
                                            </div>
                                        </div>
                                        <div>
                                            <div style={{ color: '#999', fontSize: '0.75rem', marginBottom: '0.15rem' }}>Cash Buffer</div>
                                            <div style={{ color: '#444' }}>
                                                {g.cash_reserve_years ?? 2}yr reserve, {((g.cash_return_rate ?? 0.04) * 100).toFixed(1)}% cash rate
                                            </div>
                                        </div>
                                        <div>
                                            <div style={{ color: '#999', fontSize: '0.75rem', marginBottom: '0.15rem' }}>Balance Range (P10-P55)</div>
                                            <div style={{ color: '#444' }}>
                                                {formatCurrency(g.percentile10_final)} &ndash; {formatCurrency(g.percentile55_final)}
                                            </div>
                                        </div>
                                    </div>
                                    {g.phases.length > 0 && (
                                        <div style={{ borderTop: '1px solid #eee', paddingTop: '0.75rem' }}>
                                            {g.phases.map((p, idx) => (
                                                <div key={idx} style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', padding: '0.35rem 0', fontSize: '0.85rem' }}>
                                                    <span style={{ color: '#555', fontWeight: 500 }}>{p.name || `Phase ${idx + 1}`}</span>
                                                    <span style={{ color: '#888' }}>
                                                        Ages {p.start_age}-{p.end_age ?? '\u221E'}
                                                        {p.target_spending != null ? ` \u00b7 ${formatCurrency(p.target_spending)}/yr target` : ` \u00b7 Priority: ${p.priority_weight === 3 ? 'High' : p.priority_weight === 2 ? 'Medium' : 'Low'}`}
                                                    </span>
                                                </div>
                                            ))}
                                        </div>
                                    )}
                                </div>
                            );
                        })}
                    </div>
                </div>
            )}
        </div>
    );
}
