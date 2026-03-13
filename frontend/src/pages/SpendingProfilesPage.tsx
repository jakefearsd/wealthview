import { useState, useCallback } from 'react';
import { listSpendingProfiles, createSpendingProfile, updateSpendingProfile, deleteSpendingProfile } from '../api/spendingProfiles';
import { useApiQuery } from '../hooks/useApiQuery';
import { useCrudForm } from '../hooks/useCrudForm';
import { cardStyle, inputStyle, labelStyle } from '../utils/styles';
import { formatCurrency, formatCurrencyInput, parseCurrencyInput } from '../utils/format';
import HelpText from '../components/HelpText';
import type { SpendingProfile, CreateSpendingProfileRequest, SpendingTier } from '../types/projection';

function defaultSpendingTier(): SpendingTier {
    return { name: '', start_age: 55, end_age: null, essential_expenses: 0, discretionary_expenses: 0 };
}

const initialFormData: CreateSpendingProfileRequest = {
    name: '',
    essential_expenses: 40000,
    discretionary_expenses: 20000,
    spending_tiers: [],
};

export default function SpendingProfilesPage() {
    const { data: profiles, loading, refetch } = useApiQuery(listSpendingProfiles);
    const [showForm, setShowForm] = useState(false);

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
                            <input style={inputStyle} type="text" inputMode="decimal" value={formatCurrencyInput(essentialExpenses)} onChange={e => setFormData(prev => ({ ...prev, essential_expenses: Number(parseCurrencyInput(e.target.value)) || 0 }))} />
                            <HelpText>Default non-negotiable annual costs when no spending tier matches the current age.</HelpText>
                        </div>
                        <div>
                            <label style={labelStyle}>Discretionary Expenses (annual)</label>
                            <input style={inputStyle} type="text" inputMode="decimal" value={formatCurrencyInput(discretionaryExpenses)} onChange={e => setFormData(prev => ({ ...prev, discretionary_expenses: Number(parseCurrencyInput(e.target.value)) || 0 }))} />
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
                                <input style={inputStyle} type="number" value={tier.start_age} onChange={e => updateTier(idx, 'start_age', Number(e.target.value))} />
                            </div>
                            <div>
                                <label style={labelStyle}>End Age (blank = forever)</label>
                                <input style={inputStyle} type="number" value={tier.end_age ?? ''} onChange={e => updateTier(idx, 'end_age', e.target.value ? Number(e.target.value) : null)} />
                            </div>
                            <div>
                                <label style={labelStyle}>Essential (annual)</label>
                                <input style={inputStyle} type="text" inputMode="decimal" value={formatCurrencyInput(tier.essential_expenses)} onChange={e => updateTier(idx, 'essential_expenses', Number(parseCurrencyInput(e.target.value)) || 0)} />
                            </div>
                            <div>
                                <label style={labelStyle}>Discretionary (annual)</label>
                                <input style={inputStyle} type="text" inputMode="decimal" value={formatCurrencyInput(tier.discretionary_expenses)} onChange={e => updateTier(idx, 'discretionary_expenses', Number(parseCurrencyInput(e.target.value)) || 0)} />
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
        </div>
    );
}
