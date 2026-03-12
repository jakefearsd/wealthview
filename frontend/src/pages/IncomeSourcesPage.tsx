import { useState, useCallback } from 'react';
import { listIncomeSources, createIncomeSource, updateIncomeSource, deleteIncomeSource } from '../api/incomeSources';
import { listProperties } from '../api/properties';
import { useApiQuery } from '../hooks/useApiQuery';
import { useCrudForm } from '../hooks/useCrudForm';
import { cardStyle, inputStyle, labelStyle } from '../utils/styles';
import { formatCurrency, formatCurrencyInput, parseCurrencyInput } from '../utils/format';
import HelpText from '../components/HelpText';
import InfoSection from '../components/InfoSection';
import type { IncomeSource, CreateIncomeSourceRequest } from '../types/projection';

const INCOME_TYPES = [
    { value: 'social_security', label: 'Social Security' },
    { value: 'pension', label: 'Pension' },
    { value: 'rental_property', label: 'Rental Property' },
    { value: 'part_time_work', label: 'Part-Time Work / Consulting' },
    { value: 'annuity', label: 'Annuity' },
    { value: 'other', label: 'Other' },
];

const TAX_TREATMENTS: Record<string, { value: string; label: string; description: string }[]> = {
    social_security: [
        { value: 'partially_taxable', label: 'Partially Taxable', description: 'IRS formula determines 0-85% taxable based on provisional income.' },
    ],
    pension: [
        { value: 'taxable', label: 'Fully Taxable', description: 'Most pensions are fully taxable as ordinary income.' },
        { value: 'tax_free', label: 'Tax-Free', description: 'Some government pensions may be partially or fully tax-exempt.' },
    ],
    rental_property: [
        { value: 'rental_passive', label: 'Passive', description: 'Losses only offset other passive income ($25k exception for MAGI < $150k).' },
        { value: 'rental_active_reps', label: 'Active - REPS', description: 'Real Estate Professional Status: all losses offset any income type.' },
        { value: 'rental_active_str', label: 'Active - STR', description: 'Short-Term Rental loophole: losses offset any income type.' },
    ],
    part_time_work: [
        { value: 'self_employment', label: 'Self-Employment', description: 'Subject to 15.3% SE tax (Social Security + Medicare) on 92.35% of net.' },
        { value: 'taxable', label: 'W-2 Income', description: 'Taxed as ordinary income. Employer handles payroll taxes.' },
    ],
    annuity: [
        { value: 'taxable', label: 'Fully Taxable', description: 'Qualified annuity distributions taxed as ordinary income.' },
        { value: 'tax_free', label: 'Tax-Free', description: 'Roth annuity or return of after-tax basis.' },
    ],
    other: [
        { value: 'taxable', label: 'Taxable', description: 'Ordinary income subject to federal income tax.' },
        { value: 'tax_free', label: 'Tax-Free', description: 'Not subject to income tax (e.g., gifts, Roth distributions).' },
    ],
};

function defaultTaxTreatment(incomeType: string): string {
    const treatments = TAX_TREATMENTS[incomeType];
    return treatments?.[0]?.value ?? 'taxable';
}

const TYPE_COLORS: Record<string, string> = {
    social_security: '#1565c0',
    pension: '#6a1b9a',
    rental_property: '#2e7d32',
    part_time_work: '#e65100',
    annuity: '#00838f',
    other: '#546e7a',
};

function typeLabel(type: string): string {
    return INCOME_TYPES.find(t => t.value === type)?.label ?? type;
}

function treatmentLabel(treatment: string): string {
    for (const treatments of Object.values(TAX_TREATMENTS)) {
        const found = treatments.find(t => t.value === treatment);
        if (found) return found.label;
    }
    return treatment;
}

interface IncomeSourceFormData {
    name: string;
    income_type: string;
    annual_amount: number;
    start_age: number;
    end_age: number | null;
    inflation_rate: number;
    one_time: boolean;
    tax_treatment: string;
    property_id: string | null;
}

const initialFormData: IncomeSourceFormData = {
    name: '',
    income_type: 'social_security',
    annual_amount: 0,
    start_age: 67,
    end_age: null,
    inflation_rate: 0,
    one_time: false,
    tax_treatment: 'partially_taxable',
    property_id: null,
};

export default function IncomeSourcesPage() {
    const { data: sources, loading, refetch } = useApiQuery(listIncomeSources);
    const { data: properties } = useApiQuery(listProperties);
    const [showForm, setShowForm] = useState(false);

    const onSuccess = useCallback(() => {
        setShowForm(false);
        refetch();
    }, [refetch]);

    const createFn = useCallback(async (data: IncomeSourceFormData): Promise<IncomeSource> => {
        const request: CreateIncomeSourceRequest = {
            name: data.name,
            income_type: data.income_type,
            annual_amount: data.annual_amount,
            start_age: data.start_age,
            end_age: data.one_time ? data.start_age + 1 : data.end_age,
            inflation_rate: data.one_time ? 0 : data.inflation_rate,
            one_time: data.one_time,
            tax_treatment: data.tax_treatment,
            property_id: data.income_type === 'rental_property' ? data.property_id : null,
        };
        return createIncomeSource(request);
    }, []);

    const updateFn = useCallback(async (id: string, data: IncomeSourceFormData): Promise<IncomeSource> => {
        return updateIncomeSource(id, {
            name: data.name,
            income_type: data.income_type,
            annual_amount: data.annual_amount,
            start_age: data.start_age,
            end_age: data.one_time ? data.start_age + 1 : data.end_age,
            inflation_rate: data.one_time ? 0 : data.inflation_rate,
            one_time: data.one_time,
            tax_treatment: data.tax_treatment,
            property_id: data.income_type === 'rental_property' ? data.property_id : null,
        });
    }, []);

    const { editingId, formData, setFormData, isSubmitting: saving, handleSave, handleDelete, resetForm: crudReset, startEdit: crudStartEdit } = useCrudForm<IncomeSource, IncomeSourceFormData>({
        createFn,
        updateFn,
        deleteFn: deleteIncomeSource,
        entityName: 'Income source',
        initialFormData,
        onSuccess,
        validate: (data) => {
            if (!data.name) return 'Name is required';
            if (data.annual_amount <= 0) return 'Annual amount must be greater than 0';
            return undefined;
        },
    });

    const resetForm = useCallback(() => {
        crudReset();
        setShowForm(false);
    }, [crudReset]);

    function startEdit(source: IncomeSource) {
        crudStartEdit(source.id, {
            name: source.name,
            income_type: source.income_type,
            annual_amount: source.annual_amount,
            start_age: source.start_age,
            end_age: source.end_age,
            inflation_rate: source.inflation_rate,
            one_time: source.one_time,
            tax_treatment: source.tax_treatment,
            property_id: source.property_id,
        });
        setShowForm(true);
    }

    function handleTypeChange(newType: string) {
        setFormData(prev => {
            const updates: Partial<IncomeSourceFormData> = {
                income_type: newType,
                tax_treatment: defaultTaxTreatment(newType),
            };
            if (newType !== 'rental_property') {
                updates.property_id = null;
            }
            if (newType === 'social_security') {
                updates.start_age = 67;
                updates.inflation_rate = 0.02;
            } else if (newType === 'rental_property') {
                updates.inflation_rate = 0.02;
            }
            return { ...prev, ...updates };
        });
    }

    if (loading) return <div>Loading...</div>;

    const investmentProperties = properties?.filter(p => p.property_type === 'investment') ?? [];
    const grouped = (sources ?? []).reduce<Record<string, IncomeSource[]>>((acc, s) => {
        (acc[s.income_type] = acc[s.income_type] || []).push(s);
        return acc;
    }, {});

    const { name, income_type: incomeType, annual_amount: annualAmount, start_age: startAge, end_age: endAge, inflation_rate: inflationRate, one_time: oneTime, tax_treatment: taxTreatment, property_id: propertyId } = formData;
    const treatments = TAX_TREATMENTS[incomeType] ?? TAX_TREATMENTS.other;

    return (
        <div>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '1.5rem' }}>
                <div>
                    <h2>Income Sources</h2>
                    <div style={{ fontSize: '0.85rem', color: '#666', marginTop: '0.25rem' }}>
                        Define non-portfolio income (Social Security, pensions, rental income, part-time work) that reduces how much you withdraw from investments in retirement.
                    </div>
                </div>
                <button
                    onClick={() => { if (showForm) resetForm(); else setShowForm(true); }}
                    style={{ padding: '0.5rem 1rem', background: '#1976d2', color: '#fff', border: 'none', borderRadius: '4px', cursor: 'pointer' }}
                >
                    {showForm ? 'Cancel' : 'New Income Source'}
                </button>
            </div>

            <InfoSection prompt="How do income sources work in projections?">
                Income sources reduce how much you need to withdraw from your investment portfolio each year. They are separate from spending profiles (which define your expenses). Each income source has a type that determines how it is taxed in projections. You can reuse income sources across multiple scenarios — attach them when you create or edit a projection scenario.
            </InfoSection>

            {showForm && (
                <div style={{ ...cardStyle, marginBottom: '1.5rem' }}>
                    <h3 style={{ marginBottom: '1rem' }}>{editingId ? 'Edit Income Source' : 'Create Income Source'}</h3>

                    <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '1rem', marginBottom: '1rem' }}>
                        <div>
                            <label style={labelStyle}>Name</label>
                            <input style={inputStyle} value={name} onChange={e => setFormData(prev => ({ ...prev, name: e.target.value }))} placeholder="e.g., Social Security" />
                        </div>
                        <div>
                            <label style={labelStyle}>Income Type</label>
                            <select style={inputStyle} value={incomeType} onChange={e => handleTypeChange(e.target.value)}>
                                {INCOME_TYPES.map(t => (
                                    <option key={t.value} value={t.value}>{t.label}</option>
                                ))}
                            </select>
                            <HelpText>Type determines how this income is taxed in projections.</HelpText>
                        </div>
                    </div>

                    {incomeType === 'rental_property' && (
                        <>
                            <div style={{ marginBottom: '1rem' }}>
                                <label style={labelStyle}>Link to Property (optional)</label>
                                <select style={inputStyle} value={propertyId ?? ''} onChange={e => setFormData(prev => ({ ...prev, property_id: e.target.value || null }))}>
                                    <option value="">No linked property (hypothetical)</option>
                                    {investmentProperties.map(p => (
                                        <option key={p.id} value={p.id}>{p.address} — {formatCurrency(p.current_value)}</option>
                                    ))}
                                </select>
                                <HelpText>Link to a property to pull depreciation data into projections. Leave unlinked for hypothetical planning.</HelpText>
                            </div>
                            <InfoSection prompt="What is rental property tax treatment?">
                                <div style={{ marginBottom: '0.5rem' }}><strong>Passive (default):</strong> Rental losses can only offset other passive income. There is a $25,000 exception if your MAGI is below $100,000, phased out completely at $150,000.</div>
                                <div style={{ marginBottom: '0.5rem' }}><strong>REPS (Real Estate Professional Status):</strong> If you qualify (750+ hours, &gt;50% of services in real estate), all rental losses offset any income type. Discuss with your CPA.</div>
                                <div><strong>STR (Short-Term Rental):</strong> If average guest stay is 7 days or less and you materially participate (100+ hours), losses offset any income. Discuss with your CPA.</div>
                            </InfoSection>
                        </>
                    )}

                    {incomeType === 'social_security' && (
                        <InfoSection prompt="How is Social Security taxed?">
                            Social Security benefits are taxed based on "provisional income" (your other income + 50% of SS benefits). For single filers: below $25,000 = 0% taxable; $25,000-$34,000 = up to 50% taxable; above $34,000 = up to 85% taxable. For married filing jointly: thresholds are $32,000 and $44,000. The projection engine applies this formula automatically.
                        </InfoSection>
                    )}

                    {incomeType === 'part_time_work' && (
                        <InfoSection prompt="What is self-employment tax?">
                            Self-employment income is subject to a 15.3% SE tax (12.4% Social Security + 2.9% Medicare) on 92.35% of net earnings. The Social Security portion caps at the annual wage base (~$168,600 in 2024). Half of SE tax is deductible from AGI. W-2 income avoids SE tax because employers handle payroll taxes.
                        </InfoSection>
                    )}

                    <div style={{ marginBottom: '1rem' }}>
                        <label style={labelStyle}>Tax Treatment</label>
                        <div style={{ display: 'grid', gridTemplateColumns: treatments.length > 2 ? '1fr 1fr 1fr' : '1fr 1fr', gap: '0.75rem' }}>
                            {treatments.map(t => (
                                <div
                                    key={t.value}
                                    onClick={() => setFormData(prev => ({ ...prev, tax_treatment: t.value }))}
                                    style={{
                                        border: `2px solid ${taxTreatment === t.value ? '#1976d2' : '#e0e0e0'}`,
                                        background: taxTreatment === t.value ? '#e3f2fd' : '#fff',
                                        cursor: 'pointer',
                                        borderRadius: '8px',
                                        padding: '0.75rem',
                                    }}
                                >
                                    <div style={{ fontWeight: 600, fontSize: '0.85rem', marginBottom: '0.25rem' }}>{t.label}</div>
                                    <div style={{ fontSize: '0.75rem', color: '#666', lineHeight: 1.3 }}>{t.description}</div>
                                </div>
                            ))}
                        </div>
                    </div>

                    <div style={{ display: 'flex', alignItems: 'center', gap: '0.5rem', marginBottom: '1rem' }}>
                        <input type="checkbox" id="oneTime" checked={oneTime} onChange={e => setFormData(prev => ({ ...prev, one_time: e.target.checked }))} />
                        <label htmlFor="oneTime" style={{ fontSize: '0.85rem' }}>One-time payment (e.g., deferred compensation, inheritance)</label>
                    </div>

                    <div style={{ display: 'grid', gridTemplateColumns: oneTime ? '1fr 1fr' : '1fr 1fr 1fr 1fr', gap: '1rem', marginBottom: '1rem' }}>
                        <div>
                            <label style={labelStyle}>{oneTime ? 'Payment Amount' : 'Annual Amount'}</label>
                            <input style={inputStyle} type="text" inputMode="decimal" value={formatCurrencyInput(annualAmount)} onChange={e => setFormData(prev => ({ ...prev, annual_amount: Number(parseCurrencyInput(e.target.value)) || 0 }))} />
                        </div>
                        <div>
                            <label style={labelStyle}>{oneTime ? 'Payment Age' : 'Start Age'}</label>
                            <input style={inputStyle} type="number" value={startAge} onChange={e => setFormData(prev => ({ ...prev, start_age: Number(e.target.value) }))} />
                            <HelpText>{oneTime ? 'Age when the one-time payment occurs.' : 'Age when this income begins.'}</HelpText>
                        </div>
                        {!oneTime && (
                            <>
                                <div>
                                    <label style={labelStyle}>End Age (blank = forever)</label>
                                    <input style={inputStyle} type="number" value={endAge ?? ''} onChange={e => setFormData(prev => ({ ...prev, end_age: e.target.value ? Number(e.target.value) : null }))} />
                                    <HelpText>Leave blank if this income continues for life.</HelpText>
                                </div>
                                <div>
                                    <label style={labelStyle}>Inflation Rate</label>
                                    <input style={inputStyle} type="number" step="0.001" value={inflationRate} onChange={e => setFormData(prev => ({ ...prev, inflation_rate: Number(e.target.value) || 0 }))} />
                                    <HelpText>Annual adjustment rate (e.g., 0.02 = 2%). SS COLA is typically ~2%.</HelpText>
                                </div>
                            </>
                        )}
                    </div>

                    <button
                        onClick={handleSave}
                        disabled={saving}
                        style={{ padding: '0.5rem 1rem', background: '#1976d2', color: '#fff', border: 'none', borderRadius: '4px', cursor: 'pointer' }}
                    >
                        {saving ? 'Saving...' : (editingId ? 'Update Income Source' : 'Create Income Source')}
                    </button>
                </div>
            )}

            {(sources ?? []).length === 0 ? (
                <div style={{ ...cardStyle, textAlign: 'center', padding: '3rem' }}>
                    <div style={{ color: '#999', fontSize: '1.1rem' }}>No income sources yet. Create one to attach to your retirement scenarios.</div>
                </div>
            ) : (
                Object.entries(grouped).map(([type, items]) => (
                    <div key={type} style={{ marginBottom: '1.5rem' }}>
                        <h3 style={{ color: TYPE_COLORS[type] ?? '#333', marginBottom: '0.75rem' }}>{typeLabel(type)}</h3>
                        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(340px, 1fr))', gap: '1rem' }}>
                            {items.map(s => (
                                <div key={s.id} style={cardStyle}>
                                    <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', marginBottom: '0.5rem' }}>
                                        <span style={{ fontWeight: 600, fontSize: '1.05rem' }}>{s.name}</span>
                                        <div style={{ display: 'flex', gap: '0.5rem' }}>
                                            <button
                                                onClick={() => startEdit(s)}
                                                style={{ background: 'none', border: 'none', color: '#1976d2', cursor: 'pointer', fontSize: '0.85rem' }}
                                            >
                                                Edit
                                            </button>
                                            <button
                                                onClick={() => handleDelete(s.id)}
                                                style={{ background: 'none', border: 'none', color: '#d32f2f', cursor: 'pointer', fontSize: '0.85rem' }}
                                            >
                                                Delete
                                            </button>
                                        </div>
                                    </div>
                                    <div style={{ display: 'flex', gap: '1rem', flexWrap: 'wrap', fontSize: '0.9rem', color: '#444', marginBottom: '0.25rem' }}>
                                        <div>
                                            <span style={{ color: '#999' }}>{s.one_time ? 'Amount:' : 'Annual:'}</span>{' '}
                                            {formatCurrency(s.annual_amount)}
                                        </div>
                                        <div>
                                            <span style={{ color: '#999' }}>{s.one_time ? 'Age:' : 'Ages:'}</span>{' '}
                                            {s.one_time ? s.start_age : `${s.start_age}-${s.end_age ?? '\u221E'}`}
                                        </div>
                                        {!s.one_time && s.inflation_rate > 0 && (
                                            <div>
                                                <span style={{ color: '#999' }}>Adj:</span> {(s.inflation_rate * 100).toFixed(1)}%
                                            </div>
                                        )}
                                    </div>
                                    <div style={{ fontSize: '0.8rem', color: '#888' }}>
                                        {treatmentLabel(s.tax_treatment)}
                                        {s.property_address && ` \u2022 ${s.property_address}`}
                                    </div>
                                </div>
                            ))}
                        </div>
                    </div>
                ))
            )}

            <InfoSection prompt="Talk to your CPA about...">
                <ul style={{ margin: 0, paddingLeft: '1.25rem', lineHeight: 1.8 }}>
                    <li>Whether you qualify for Real Estate Professional Status (REPS) based on your hours and activities</li>
                    <li>Whether your short-term rental qualifies for the STR loophole (&le;7 day average stay + material participation)</li>
                    <li>Whether a cost segregation study makes sense for your property (accelerated depreciation, typical ROI 5-10x)</li>
                    <li>Your provisional income estimate to understand Social Security taxability</li>
                    <li>Optimal Roth conversion strategy in years when rental losses shield income from taxes</li>
                </ul>
                <div style={{ marginTop: '0.75rem', fontStyle: 'italic', color: '#888' }}>
                    WealthView provides planning estimates only, not tax advice. All tax calculations are approximations.
                </div>
            </InfoSection>
        </div>
    );
}
