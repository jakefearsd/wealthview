import { useState } from 'react';
import { listSpendingProfiles, createSpendingProfile, updateSpendingProfile, deleteSpendingProfile } from '../api/spendingProfiles';
import { useApiQuery } from '../hooks/useApiQuery';
import { cardStyle } from '../utils/styles';
import { formatCurrency } from '../utils/format';
import toast from 'react-hot-toast';
import { extractErrorMessage } from '../utils/errorMessage';
import HelpText from '../components/HelpText';
import type { SpendingProfile, CreateSpendingProfileRequest, IncomeStream } from '../types/projection';

const inputStyle = { padding: '0.5rem', border: '1px solid #ccc', borderRadius: '4px', width: '100%' };
const labelStyle = { display: 'block', marginBottom: '0.25rem', fontWeight: 600 as const, fontSize: '0.85rem' };

function defaultIncomeStream(): IncomeStream {
    return { name: '', annual_amount: 0, start_age: 65, end_age: null };
}

export default function SpendingProfilesPage() {
    const { data: profiles, loading, refetch } = useApiQuery(listSpendingProfiles);
    const [showForm, setShowForm] = useState(false);
    const [editingId, setEditingId] = useState<string | null>(null);
    const [saving, setSaving] = useState(false);

    const [name, setName] = useState('');
    const [essentialExpenses, setEssentialExpenses] = useState(40000);
    const [discretionaryExpenses, setDiscretionaryExpenses] = useState(20000);
    const [incomeStreams, setIncomeStreams] = useState<IncomeStream[]>([]);

    function resetForm() {
        setName('');
        setEssentialExpenses(40000);
        setDiscretionaryExpenses(20000);
        setIncomeStreams([]);
        setEditingId(null);
        setShowForm(false);
    }

    function startEdit(profile: SpendingProfile) {
        setName(profile.name);
        setEssentialExpenses(profile.essential_expenses);
        setDiscretionaryExpenses(profile.discretionary_expenses);
        setIncomeStreams(profile.income_streams.length > 0 ? [...profile.income_streams] : []);
        setEditingId(profile.id);
        setShowForm(true);
    }

    function updateStream(index: number, field: keyof IncomeStream, value: string | number | null) {
        setIncomeStreams(prev => prev.map((s, i) => i === index ? { ...s, [field]: value } : s));
    }

    async function handleSave() {
        if (!name) {
            toast.error('Name is required');
            return;
        }
        setSaving(true);
        try {
            const request: CreateSpendingProfileRequest = {
                name,
                essential_expenses: essentialExpenses,
                discretionary_expenses: discretionaryExpenses,
                income_streams: incomeStreams,
            };
            if (editingId) {
                await updateSpendingProfile(editingId, request);
                toast.success('Profile updated');
            } else {
                await createSpendingProfile(request);
                toast.success('Profile created');
            }
            resetForm();
            refetch();
        } catch (err: unknown) {
            toast.error(extractErrorMessage(err));
        } finally {
            setSaving(false);
        }
    }

    async function handleDelete(id: string) {
        try {
            await deleteSpendingProfile(id);
            toast.success('Profile deleted');
            refetch();
        } catch (err: unknown) {
            toast.error(extractErrorMessage(err));
        }
    }

    if (loading) return <div>Loading...</div>;

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
                            <input style={inputStyle} value={name} onChange={e => setName(e.target.value)} placeholder="Retirement Spending" />
                        </div>
                        <div>
                            <label style={labelStyle}>Essential Expenses (annual)</label>
                            <input style={inputStyle} type="number" value={essentialExpenses} onChange={e => setEssentialExpenses(Number(e.target.value))} />
                            <HelpText>Non-negotiable annual costs: housing, food, healthcare, insurance. Always fully funded — never reduced.</HelpText>
                        </div>
                        <div>
                            <label style={labelStyle}>Discretionary Expenses (annual)</label>
                            <input style={inputStyle} type="number" value={discretionaryExpenses} onChange={e => setDiscretionaryExpenses(Number(e.target.value))} />
                            <HelpText>Flexible annual spending: travel, entertainment, dining out. Reduced first if withdrawals fall short.</HelpText>
                        </div>
                    </div>

                    <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '0.5rem' }}>
                        <div>
                            <h4>Income Streams</h4>
                            <div style={{ fontSize: '0.8rem', color: '#666', marginTop: '0.15rem' }}>Non-portfolio income sources that reduce how much you need to withdraw from investments.</div>
                        </div>
                        <button
                            onClick={() => setIncomeStreams(prev => [...prev, defaultIncomeStream()])}
                            style={{ padding: '0.25rem 0.75rem', background: '#4caf50', color: '#fff', border: 'none', borderRadius: '4px', cursor: 'pointer', fontSize: '0.85rem' }}
                        >
                            + Add Income Stream
                        </button>
                    </div>
                    {incomeStreams.map((stream, idx) => (
                        <div key={idx} style={{ display: 'grid', gridTemplateColumns: '2fr 1fr 1fr 1fr auto', gap: '1rem', marginBottom: '0.75rem', alignItems: 'end' }}>
                            <div>
                                <label style={labelStyle}>Name</label>
                                <input style={inputStyle} value={stream.name} onChange={e => updateStream(idx, 'name', e.target.value)} placeholder="Social Security" />
                            </div>
                            <div>
                                <label style={labelStyle}>Annual Amount</label>
                                <input style={inputStyle} type="number" value={stream.annual_amount} onChange={e => updateStream(idx, 'annual_amount', Number(e.target.value))} />
                            </div>
                            <div>
                                <label style={labelStyle}>Start Age</label>
                                <input style={inputStyle} type="number" value={stream.start_age} onChange={e => updateStream(idx, 'start_age', Number(e.target.value))} />
                                <HelpText>Age when this income begins (e.g., 67 for Social Security).</HelpText>
                            </div>
                            <div>
                                <label style={labelStyle}>End Age (blank = forever)</label>
                                <input style={inputStyle} type="number" value={stream.end_age ?? ''} onChange={e => updateStream(idx, 'end_age', e.target.value ? Number(e.target.value) : null)} />
                                <HelpText>Leave blank if this income continues for life.</HelpText>
                            </div>
                            <div>
                                <button
                                    onClick={() => setIncomeStreams(prev => prev.filter((_, i) => i !== idx))}
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
                <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(340px, 1fr))', gap: '1rem' }}>
                    {profiles?.map(p => (
                        <div key={p.id} style={cardStyle}>
                            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', marginBottom: '0.75rem' }}>
                                <span style={{ fontWeight: 600, fontSize: '1.1rem' }}>{p.name}</span>
                                <div style={{ display: 'flex', gap: '0.5rem' }}>
                                    <button
                                        onClick={() => startEdit(p)}
                                        style={{ background: 'none', border: 'none', color: '#1976d2', cursor: 'pointer', fontSize: '0.85rem' }}
                                    >
                                        Edit
                                    </button>
                                    <button
                                        onClick={() => handleDelete(p.id)}
                                        style={{ background: 'none', border: 'none', color: '#d32f2f', cursor: 'pointer', fontSize: '0.85rem' }}
                                    >
                                        Delete
                                    </button>
                                </div>
                            </div>
                            <div style={{ display: 'flex', gap: '1.5rem', marginBottom: '0.5rem', fontSize: '0.9rem', color: '#444' }}>
                                <div><span style={{ color: '#999' }}>Essential:</span> {formatCurrency(p.essential_expenses)}</div>
                                <div><span style={{ color: '#999' }}>Discretionary:</span> {formatCurrency(p.discretionary_expenses)}</div>
                            </div>
                            {p.income_streams.length > 0 && (
                                <div style={{ fontSize: '0.85rem', color: '#666' }}>
                                    <span style={{ color: '#999' }}>Income:</span>{' '}
                                    {p.income_streams.map(s => `${s.name} (${formatCurrency(s.annual_amount)})`).join(', ')}
                                </div>
                            )}
                        </div>
                    ))}
                </div>
            )}
        </div>
    );
}
